// Copyright (c) 2025 RethinkDNS and its authors.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package trie

import (
	"fmt"
	"math"
)

type rankdir struct {
	dir         *binstr
	data        *binstr
	l1Size      int
	l2Size      int
	l1Bits      int
	l2Bits      int
	sectionBits int
	numBits     int
}

func newRankDir(rd, td *binstr, numBits int, l1Size int, l2Size int) *rankdir {
	l1Bits := int(math.Ceil(math.Log2(float64(numBits))))
	l2Bits := int(math.Ceil(math.Log2(float64(l1Size))))
	rdir := &rankdir{
		dir:         rd,
		data:        td,
		l1Size:      l1Size,
		l2Size:      l2Size,
		l1Bits:      l1Bits,
		l2Bits:      l2Bits,
		sectionBits: (l1Size/l2Size-1)*l2Bits + l1Bits,
		numBits:     numBits,
	}
	if debug {
		rdir.display()
	}
	return rdir
}

func (rdir *rankdir) display() {
	fmt.Println("trie: td dir sz: ", rdir.dir.Size())
	fmt.Println("trie: rd data sz: ", rdir.data.Size())
	fmt.Println("trie: rd numBits: ", rdir.numBits)
	fmt.Println("trie: rd l1size: ", rdir.l1Size)
	fmt.Println("trie: rd l1bits: ", rdir.l1Bits)
	fmt.Println("trie: rd l2bits: ", rdir.l2Bits)
}

func (rdir *rankdir) rank(_, x int) int {
	var temp uint32
	rank := -1
	sectionPos := 0
	if x >= rdir.l2Size {
		sectionPos = (x / rdir.l2Size) * rdir.l1Bits
		temp = rdir.dir.get(sectionPos-rdir.l1Bits, rdir.l1Bits)
		rank = int(temp)
		x = x % rdir.l2Size
	}
	var ans = 0
	if x > 0 {
		ans = rdir.data.pos0(rank+1, x)
	} else {
		ans = rank
	}
	if debug {
		fmt.Printf("trie: ans: %d %d:r, x: %d %d:s %d:l1 %t:ifcheck\n",
			ans, temp, x, sectionPos, rdir.l1Bits, x >= rdir.l2Size)
	}
	return ans
}
