// Copyright (c) 2025 RethinkDNS and its authors.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package trie

import (
	"bytes"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"runtime"
	"syscall"
	"time"
	"unsafe"
)

const (
	debug = false
	W     = 16
	L1    = 32 * 32
	L2    = 32
)

type ReadKind bool

const Fmmap ReadKind = true
const Ffull ReadKind = false

func (rk ReadKind) String() string {
	if rk {
		return "mmap"
	}
	return "full"
}

func Build(tdpath, rdpath, bcpath, ftpath string, rk ReadKind) (ftrie *FrozenTrie, err error) {
	tdstart := time.Now()
	td16, td8, err := readBinary(tdpath, rk)
	tdelapsed := time.Since(tdstart).Milliseconds()
	if err != nil {
		return
	}

	rdstart := time.Now()
	rd16, rd8, err := readBinary(rdpath, rk)
	rdelapsed := time.Since(rdstart).Milliseconds()
	if err != nil {
		return
	}

	bconfig, err := readBasicConfig(bcpath)
	if err != nil {
		return
	}

	nodecount := int(bconfig["nodecount"].(float64))

	tdmd5hex := md5hex(td8)
	rdmd5hex := md5hex(rd8)
	tdmd5, _ := bconfig["tdmd5"].(string)
	rdmd5, _ := bconfig["rdmd5"].(string)
	hstatus := fmt.Sprintf("trie: md5 mismatch: %s <=> %s | %s <=> %s",
		tdmd5hex, tdmd5, rdmd5hex, rdmd5)

	if tdmd5hex != tdmd5 || rdmd5hex != rdmd5 {
		err = errors.New(hstatus)
		return
	}

	rdb := asBinaryString(rd16)
	tdb := asBinaryString(td16)
	rdir := newRankDir(rdb, tdb, nodecount*2+1, L1, L2)
	ftrie = NewFrozenTrie(tdb, rdir, nodecount, ftpath)

	if rk == Fmmap {
		runtime.AddCleanup(ftrie, func(arr []*[]byte) {
			for _, b := range arr {
				err := syscall.Munmap(*b)
				fmt.Println("trie: munmap! err? ", err)
			}

		}, []*[]byte{td8, rd8})
	}

	fmt.Println("trie: td loadtime:", tdelapsed, "rd loadtime:", rdelapsed, "rk:", rk)

	return
}

// mmapBinary mmaps the binary file and returns the uint16 and byte slices
func mmapBinary(path string) (u16 *[]uint16, u8 *[]byte, err error) {
	if path == "" {
		err = errors.New("trie: empty path")
		return
	}

	if !lilbo(false) {
		err = errors.New("trie: mmap on little endian only")
		return
	}

	// Open the file
	file, err := os.Open(path)
	if err != nil {
		return
	}
	defer file.Close()

	// Get the file size
	fi, err := file.Stat()
	if err != nil {
		return
	}
	fd := int(file.Fd())
	size := fi.Size()

	if int64(int(size)) != size {
		err = errors.New("trie: file too large")
		return
	}

	data, err := syscall.Mmap(fd, 0, int(size), syscall.PROT_READ, syscall.MAP_PRIVATE)
	if err != nil {
		return
	}

	fmt.Println("trie: mmaped", path, "size", len(data))

	return bytesToUint16(&data), &data, nil
}

func readBinary(path string, rk ReadKind) (u16 *[]uint16, u8 *[]byte, err error) {
	isLittleEndian := lilbo(false)
	if rk == Fmmap && isLittleEndian {
		return mmapBinary(path)
	}

	content, err := os.ReadFile(path)
	if err != nil {
		err = fmt.Errorf("trie: err read file: %v", err)
		return
	}
	sz := len(content)

	fmt.Printf("trie: read from %s len %d\n", path, sz)

	// works only on little endian machines: go.dev/play/p/50t1HxCr9DV
	if isLittleEndian {
		return bytesToUint16(&content), &content, nil
	}

	r := bytes.NewReader(content)
	tmp16 := make([]uint16, sz/2)
	err = binary.Read(r, binary.LittleEndian, &tmp16)
	if err != nil {
		err = fmt.Errorf("trie: err: %v", err)
		return
	}
	return &tmp16, &content, nil
}

func readBasicConfig(filepath string) (map[string]any, error) {
	data, err := os.ReadFile(filepath)
	if err != nil {
		return nil, err
	}

	var jobj map[string]any
	err = json.Unmarshal(data, &jobj)
	if err != nil {
		return nil, fmt.Errorf("trie: err reading basicconfig: %v", err)
	}

	return jobj, err
}

// little endian byte order?
// stackoverflow.com/a/53286786
func lilbo(unknown bool) bool {
	buf := [2]byte{}
	*(*uint16)(unsafe.Pointer(&buf[0])) = uint16(0xABCD)

	switch buf {
	case [2]byte{0xCD, 0xAB}:
		return true
	case [2]byte{0xAB, 0xCD}:
		return false
	default:
		return unknown
	}
}
