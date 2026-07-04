package com.hitstudio.syncup.server;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@SpringBootTest(properties = {
		"syncup.discovery.enabled=false",
		"syncup.storage.root=build/test-data/context",
		"syncup.storage.minimum-free-bytes=1"
})
class SyncUpServerApplicationTests {
	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectMapper json;

	@Test
	void contextLoads() {
	}

	@Test
	void backupDeduplicateListAndRangeRestore() throws Exception {
		UUID deviceId = UUID.randomUUID();
		byte[] bytes = "hello syncup".getBytes(StandardCharsets.UTF_8);
		String sha = HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(bytes));

		String createBody = """
				{"deviceId":"%s","deviceName":"Test Phone","idempotencyKey":"run-one"}
				""".formatted(deviceId);
		String created = mvc.perform(post("/api/v1/backups")
						.contentType(MediaType.APPLICATION_JSON)
						.content(createBody))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.state").value("PREPARING"))
				.andReturn().getResponse().getContentAsString();
		UUID runId = UUID.fromString(json.readTree(created).get("runId").asText());

		String manifest = """
				{
				  "deviceId":"%s",
				  "deviceName":"Test Phone",
				  "files":[{
				    "clientFileKey":"photo-1",
				    "displayName":"hello.txt",
				    "relativePath":"Documents/hello.txt",
				    "mediaType":"DOCUMENT",
				    "mimeType":"text/plain",
				    "sizeBytes":%d,
				    "sha256":"%s"
				  }]
				}
				""".formatted(deviceId, bytes.length, sha);
		String plan = mvc.perform(post("/api/v1/backups/{runId}/manifest", runId)
						.contentType(MediaType.APPLICATION_JSON)
						.content(manifest))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.files[0].disposition").value("UPLOAD"))
				.andReturn().getResponse().getContentAsString();
		UUID transferId = UUID.fromString(
				json.readTree(plan).get("files").get(0).get("transferId").asText());

		byte[] firstSegment = "hello ".getBytes(StandardCharsets.UTF_8);
		byte[] secondSegment = "syncup".getBytes(StandardCharsets.UTF_8);
				mvc.perform(put("/api/v1/transfers/{transferId}/content", transferId)
						.header("X-SyncUp-Device-Id", deviceId)
						.header("X-SyncUp-Device-Name", "Test Phone")
						.header("X-SyncUp-Run-Id", runId)
						.header("Upload-Offset", "0")
						.contentType(MediaType.APPLICATION_OCTET_STREAM)
						.content(firstSegment))
				.andExpect(status().isNoContent())
				.andExpect(header().string("Upload-Offset", Integer.toString(firstSegment.length)))
				.andExpect(header().string("Upload-Complete", "false"));

				mvc.perform(put("/api/v1/transfers/{transferId}/content", transferId)
						.header("X-SyncUp-Device-Id", deviceId)
						.header("X-SyncUp-Device-Name", "Test Phone")
						.header("X-SyncUp-Run-Id", runId)
						.header("Upload-Offset", "0")
						.contentType(MediaType.APPLICATION_OCTET_STREAM)
						.content(secondSegment))
				.andExpect(status().isConflict())
				.andExpect(header().string("Upload-Offset", Integer.toString(firstSegment.length)))
				.andExpect(jsonPath("$.code").value("UPLOAD_OFFSET_MISMATCH"));

				mvc.perform(put("/api/v1/transfers/{transferId}/content", transferId)
						.header("X-SyncUp-Device-Id", deviceId)
						.header("X-SyncUp-Device-Name", "Test Phone")
						.header("X-SyncUp-Run-Id", runId)
						.header("Upload-Offset", Integer.toString(firstSegment.length))
						.contentType(MediaType.APPLICATION_OCTET_STREAM)
						.content(secondSegment))
				.andExpect(status().isNoContent())
				.andExpect(header().string("Upload-Offset", Integer.toString(bytes.length)))
				.andExpect(header().string("Upload-Complete", "true"));

		mvc.perform(post("/api/v1/backups/{runId}/complete", runId)
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"deviceId":"%s","deviceName":"Test Phone"}
								""".formatted(deviceId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("COMPLETED"))
				.andExpect(jsonPath("$.fileCount").value(1));

		String listing = mvc.perform(get("/api/v1/files").param("deviceId", deviceId.toString()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.files[0].sha256").value(sha))
				.andReturn().getResponse().getContentAsString();
		UUID fileId = UUID.fromString(
				json.readTree(listing).get("files").get(0).get("fileId").asText());

		mvc.perform(get("/api/v1/files/{fileId}/content", fileId))
				.andExpect(status().isOk())
				.andExpect(content().bytes(bytes))
				.andExpect(header().string("Accept-Ranges", "bytes"));

		mvc.perform(get("/api/v1/files/{fileId}/content", fileId)
						.header("Range", "bytes=6-"))
				.andExpect(status().isPartialContent())
				.andExpect(header().string("Content-Range", "bytes 6-11/12"))
				.andExpect(content().bytes("syncup".getBytes(StandardCharsets.UTF_8)));

		String secondRun = mvc.perform(post("/api/v1/backups")
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"deviceId":"%s","deviceName":"Test Phone","idempotencyKey":"run-two"}
								""".formatted(deviceId)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		UUID secondRunId = UUID.fromString(json.readTree(secondRun).get("runId").asText());
		mvc.perform(post("/api/v1/backups/{runId}/manifest", secondRunId)
						.contentType(MediaType.APPLICATION_JSON)
						.content(manifest))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.files[0].disposition").value("PRESENT"))
				.andExpect(jsonPath("$.files[0].fileId").value(fileId.toString()));
	}
}
