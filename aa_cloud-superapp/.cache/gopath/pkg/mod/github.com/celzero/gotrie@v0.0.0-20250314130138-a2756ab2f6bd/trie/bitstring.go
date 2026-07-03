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

type binstr struct {
	bytes  *[]uint16
	length int
}

func asBinaryString(str *[]uint16) *binstr {
	if str == nil {
		return nil
	}

	return &binstr{
		bytes:  str,
		length: len(*str) * W,
	}
}

func (bs *binstr) Size() int {
	return bs.length
}

func (bs *binstr) get(p int, n int) uint32 {
	debug := debug

	bb := *bs.bytes
	mask := uint16(0)
	if v, ok := MaskHi[W]; ok && len(v) > p%W {
		mask = v[p%W]
	}

	if (p%W)+n <= W {
		return uint32((bb[p/W] & mask) >> (W - (p % W) - n))
		// case 2: bits lie incompletely in the given byte
	} else {
		var result uint32
		var l int
		result = uint32(bb[p/W] & mask)

		disp1 := bb[p/W]
		disp2 := mask
		var res1 = result

		l = W - p%W
		p += l
		n -= l

		for n >= W {
			result = (result << W) | uint32(bb[p/W])
			p += W
			n -= W
		}

		var res2 = result
		if n > 0 {
			result = (result << n) | uint32(bb[p/W]>>(W-n))
		}

		if debug {
			fmt.Printf("disp1: %d disp2: %d res1:%d res2:%d r:%d\n", disp1, disp2, res1, res2, result)
		}
		return result
	}
}

func (bs *binstr) pos0(i int, n int) int {
	if n < 0 {
		return 0
	}
	step := 16
	index := i
	for n > 0 {
		d := bs.get(i, step)
		bits0 := step - countSetBits(int(d))
		diff := 0
		if n-bits0 < 0 {
			step = int(math.Max(float64(n), float64(step/2)))
			continue
		}
		n -= bits0
		i += step
		if n == 0 {
			diff = Bit0(int(d), 1, step)
		} else {
			diff = 1
		}
		index = i - diff // 1;
	}
	return index
}

var MaskHi = make(map[int][]uint16)
var MaskLo = make(map[int][]uint16)
var BitsetTable256 [256]int

func countSetBits(n int) int {
	return (BitsetTable256[n&0xff] +
		BitsetTable256[(n>>8)&0xff] +
		BitsetTable256[(n>>16)&0xff] +
		BitsetTable256[n>>24])
}

func init() {
	// W is set to 16
	MaskHi[16] = []uint16{}
	MaskHi[16] = append(MaskHi[16], 0xffff)
	MaskHi[16] = append(MaskHi[16], 0x7fff)
	MaskHi[16] = append(MaskHi[16], 0x3fff)
	MaskHi[16] = append(MaskHi[16], 0x1fff)
	MaskHi[16] = append(MaskHi[16], 0x0fff)
	MaskHi[16] = append(MaskHi[16], 0x07ff)
	MaskHi[16] = append(MaskHi[16], 0x03ff)
	MaskHi[16] = append(MaskHi[16], 0x01ff)
	MaskHi[16] = append(MaskHi[16], 0x00ff)
	MaskHi[16] = append(MaskHi[16], 0x007f)
	MaskHi[16] = append(MaskHi[16], 0x003f)
	MaskHi[16] = append(MaskHi[16], 0x001f)
	MaskHi[16] = append(MaskHi[16], 0x000f)
	MaskHi[16] = append(MaskHi[16], 0x0007)
	MaskHi[16] = append(MaskHi[16], 0x0003)
	MaskHi[16] = append(MaskHi[16], 0x0001)
	MaskHi[16] = append(MaskHi[16], 0x0000)

	MaskLo[16] = []uint16{}
	MaskLo[16] = append(MaskLo[16], 0xffff)
	MaskLo[16] = append(MaskLo[16], 0xfffe)
	MaskLo[16] = append(MaskLo[16], 0xfffc)
	MaskLo[16] = append(MaskLo[16], 0xfff8)
	MaskLo[16] = append(MaskLo[16], 0xfff0)
	MaskLo[16] = append(MaskLo[16], 0xffe0)
	MaskLo[16] = append(MaskLo[16], 0xffc0)
	MaskLo[16] = append(MaskLo[16], 0xff80)
	MaskLo[16] = append(MaskLo[16], 0xff00)
	MaskLo[16] = append(MaskLo[16], 0xfe00)
	MaskLo[16] = append(MaskLo[16], 0xfc00)
	MaskLo[16] = append(MaskLo[16], 0xf800)
	MaskLo[16] = append(MaskLo[16], 0xf000)
	MaskLo[16] = append(MaskLo[16], 0xe000)
	MaskLo[16] = append(MaskLo[16], 0xc000)
	MaskLo[16] = append(MaskLo[16], 0x8000)
	MaskLo[16] = append(MaskLo[16], 0x0000)

	BitsetTable256[0] = 0
	for i := range 256 {
		j := int(float64(i / 2)) // j = i >> 1
		BitsetTable256[i] = (i & 1) + BitsetTable256[j]
	}
}
