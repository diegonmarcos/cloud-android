// Copyright (c) 2025 RethinkDNS and its authors.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package trie

import (
	"bytes"
	"crypto/md5"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"unsafe"
)

func bit0p(n int, p int) map[string]int {
	var ret = make(map[string]int)
	if p == 0 {
		ret["index"] = 0
		ret["scanned"] = 0
		return ret
	}

	if n == 0 && p == 1 {
		ret["index"] = 1
		ret["scanned"] = 1
		return ret
	}
	var c = 0
	var i = 0
	//var m = n
	for c = 0; n > 0 && p > c; n = n >> 1 {
		// increment c when nth lsb (bit) is 0
		if n < (n ^ 0x1) {
			c = c + 1
		} else {
			c = c + 0
		}
		//c = c + (n < (n ^ 0x1)) ? 1 : 0;
		i += 1
	}
	ret["scanned"] = i
	if p == c {
		ret["index"] = i
	} else {
		ret["index"] = 0
	}
	return ret
}

func Bit0(n int, p int, pad int) int {
	var r = bit0p(n, p)
	if r["scanned"] <= 0 {
		return r["scanned"]
	} // r.index
	if r["index"] > 0 {
		return r["scanned"]
	} // r.index
	if pad > r["scanned"] {
		return (r["scanned"] + 1)
	} else {
		return 0
	}
}

func dec16(str string, index int) int {
	res := int([]rune(str)[index])
	return res
}
func chr16(charcode int) string {
	return string(charcode)
}

func flagSubstr(str string, si int, ei int) string {
	res := []rune(str)
	resstr := ""
	if ei == 0 {
		resstr = string(res[si:])
	} else {
		resstr = string(res[si:ei])
	}
	return resstr
}

func flagstrToUint32(str string) []uint32 {
	runedata := []rune(str)
	resp := make([]uint32, len(runedata))
	for key, value := range runedata {
		resp[key] = uint32(value)
	}
	return resp
}

func encodeText(str string) ([]uint8, error) {
	strbytes := []byte(str)
	r := bytes.NewReader(strbytes)
	u8 := make([]uint8, len(strbytes))
	err := binary.Read(r, binary.LittleEndian, &u8)
	if err != nil {
		return nil, fmt.Errorf("trie: err TxtEncode: %v", err)

	}

	for i, j := 0, len(u8)-1; i < j; i, j = i+1, j-1 {
		u8[i], u8[j] = u8[j], u8[i]
	}

	return u8, nil
}

func dupElems(list1 []string, list2 []string) (bool, []string) {
	retlist := []string{}
	if len(list1) <= 0 || len(list2) <= 0 {
		if debug {
			fmt.Println("return false and empty retlist")
		}
		return false, retlist
	}
	if debug {
		fmt.Printf("----list len %d %d\n", len(list1), len(list2))
	}

	found := false
	for _, value := range list1 {
		// FIXME: FT.usr_bl can contain empty strings and that
		// shoudln't be matched at all. But then again commons
		// isn't the place to account for such edge-cases.
		if len(value) <= 0 {
			continue
		}
		for _, ivalue := range list2 {
			if value == ivalue {
				found = true
				retlist = append(retlist, value)
			}
		}
	}

	return found, retlist
}

// from: stackoverflow.com/a/25286918
func md5hex(b *[]byte) string {
	hash := md5.Sum(*b)
	return hex.EncodeToString(hash[:])
}

// stackoverflow.com/a/25409018
func bytesToUint16(bptr *[]byte) *[]uint16 {
	b := *bptr
	l := len(b)
	if l == 0 {
		return nil
	}
	if l%2 != 0 {
		fmt.Printf("trie: bytesToUint16: len(%d) mod 2 != 0\n", l)
	}

	sz := l / 2
	u16 := unsafe.Slice((*uint16)(unsafe.Pointer(&b[0])), sz)
	return &u16
}
