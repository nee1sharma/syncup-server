package api

import "sync"

type transferLimiter struct {
	mu        sync.Mutex
	maxTotal  int
	maxDevice int
	total     int
	devices   map[string]int
	active    map[string]bool
}

func newTransferLimiter(maxTotal, maxDevice int) *transferLimiter {
	return &transferLimiter{maxTotal: maxTotal, maxDevice: maxDevice,
		devices: make(map[string]int), active: make(map[string]bool)}
}

func (l *transferLimiter) acquire(transferID, deviceID string) (func(), string) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.active[transferID] {
		return nil, "This transfer already has an active writer"
	}
	if l.total >= l.maxTotal || l.devices[deviceID] >= l.maxDevice {
		return nil, "Transfer capacity is currently exhausted"
	}
	l.active[transferID] = true
	l.total++
	l.devices[deviceID]++
	var once sync.Once
	return func() {
		once.Do(func() {
			l.mu.Lock()
			defer l.mu.Unlock()
			delete(l.active, transferID)
			l.total--
			l.devices[deviceID]--
			if l.devices[deviceID] == 0 {
				delete(l.devices, deviceID)
			}
		})
	}, ""
}
