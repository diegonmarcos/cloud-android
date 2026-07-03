// Copyright (c) 2025 RethinkDNS and its authors.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package trie

// from: github.com/dgrijalva/lfu-go

import (
	"container/list"
	"sync"
)

type lfu[T any] struct {
	mu     *sync.Mutex
	values map[string]*cacheEntry[T]
	freqs  *list.List
	// If len > himark, cache will automatically evict
	// down to lomark.  If either value is 0, this behavior
	// is disabled.
	himark int
	lomark int
	len    int
}

type cacheEntry[T any] struct {
	key      string
	value    T
	freqNode *list.Element
}

type listEntry[T any] struct {
	entries map[*cacheEntry[T]]byte
	freq    int
}

func newLfuCache[T any](hi, lo int) *lfu[T] {
	c := new(lfu[T])
	c.values = make(map[string]*cacheEntry[T])
	c.freqs = list.New()
	c.mu = new(sync.Mutex)
	c.himark = hi
	c.lomark = lo
	return c
}

func (c *lfu[T]) Get(key string) (zz T) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if e, ok := c.values[key]; ok {
		c.tickFreqLocked(e)
		return e.value
	}
	return
}

func (c *lfu[T]) Set(key string, value T) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if e, ok := c.values[key]; ok {
		// value already exists for key.  overwrite
		e.value = value
		c.tickFreqLocked(e)
	} else {
		// value doesn't exist.  insert
		e := new(cacheEntry[T])
		e.key = key
		e.value = value
		c.values[key] = e
		c.tickFreqLocked(e)
		c.len++
		// bounds mgmt
		if c.himark > 0 && c.lomark > 0 {
			if c.len > c.himark {
				c.evictLocked(c.len - c.lomark)
			}
		}
	}
}

func (c *lfu[T]) Len() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.len
}

func (c *lfu[T]) evictLocked(count int) (evicted int) {
	for i := range count {
		place := c.freqs.Front()
		if place == nil {
			break
		}
		for entry, _ := range place.Value.(*listEntry[T]).entries {
			if i > count {
				continue
			}
			delete(c.values, entry.key)
			c.removedLocked(place, entry)
			evicted++
			c.len--
			i++
		}

	}
	return
}

func (c *lfu[T]) tickFreqLocked(e *cacheEntry[T]) {
	currentPlace := e.freqNode
	var nextFreq int
	var nextPlace *list.Element
	if currentPlace == nil {
		// new entry
		nextFreq = 1
		nextPlace = c.freqs.Front()
	} else {
		// move up
		nextFreq = currentPlace.Value.(*listEntry[T]).freq + 1
		nextPlace = currentPlace.Next()
	}

	if nextPlace == nil || nextPlace.Value.(*listEntry[T]).freq != nextFreq {
		// create a new list entry
		li := new(listEntry[T])
		li.freq = nextFreq
		li.entries = make(map[*cacheEntry[T]]byte)
		if currentPlace != nil {
			nextPlace = c.freqs.InsertAfter(li, currentPlace)
		} else {
			nextPlace = c.freqs.PushFront(li)
		}
		if nextPlace == nil { // unexpected
			return
		}
	}

	e.freqNode = nextPlace
	nextPlace.Value.(*listEntry[T]).entries[e] = 1
	if currentPlace != nil {
		// remove from current position
		c.removedLocked(currentPlace, e)
	}
}

func (c *lfu[T]) removedLocked(place *list.Element, entry *cacheEntry[T]) {
	entries := place.Value.(*listEntry[T]).entries
	delete(entries, entry)
	if len(entries) == 0 {
		c.freqs.Remove(place)
	}
}
