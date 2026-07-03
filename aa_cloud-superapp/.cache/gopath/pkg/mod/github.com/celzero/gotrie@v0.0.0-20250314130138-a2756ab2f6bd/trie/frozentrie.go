// Copyright (c) 2025 RethinkDNS and its authors.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package trie

import (
	"fmt"
	"os"
	"unsafe"

	b64 "encoding/base64"
	"encoding/binary"
	"encoding/json"
	"net/url"
	"strings"
)

const cachesz = 2100
const cacheszlo = 2000

type FrozenTrie struct {
	data        *binstr
	directory   *rankdir
	extraBit    int
	bitslen     int
	letterStart int

	rflags map[int]string
	fdata  map[string]any

	bcache *lfu[string]
	fcache *lfu[string]

	usrFlag       string
	usrBlocklists []string
}

func (f *FrozenTrie) Sizes() string {
	return fmt.Sprintf("trie: ft: %d, td: %d, rd: %d\n", unsafe.Sizeof(f), unsafe.Sizeof(f.data), unsafe.Sizeof(f.directory))
}

func NewFrozenTrie(td *binstr, rdir *rankdir, nodeCount int, tagfile string) *FrozenTrie {
	extraBit := 1 //(config.compress && !config.unroll) ? 1 : 0;
	ft := &FrozenTrie{
		data:          td,
		directory:     rdir,
		extraBit:      extraBit,
		bitslen:       9 + extraBit, //((config.base32) ? 6 : 9) + this.extraBit;
		letterStart:   nodeCount*2 + 1,
		bcache:        newLfuCache[string](cachesz, cacheszlo),
		fcache:        newLfuCache[string](cachesz, cacheszlo),
		rflags:        make(map[int]string),
		fdata:         make(map[string]any),
		usrFlag:       "",
		usrBlocklists: []string{},
	}
	ft.LoadTag(tagfile)
	return ft
}

func (f *FrozenTrie) Dispose() {
	// todo: clear fcache and bcache
}

func (f *FrozenTrie) getNodeByIndex(index int) *FrozenTrieNode {
	return NewFrozenTrieNode(f, index)
}

func (f *FrozenTrie) getRoot() *FrozenTrieNode {
	return f.getNodeByIndex(0)
}

func (f *FrozenTrie) lookup(word []uint8) (bool, []uint32) {
	var node = f.getRoot()
	var emptyreturn []uint32
	// considerably greater than the observed max-size of a node in the
	// radix-trie (18 Jan 2023): "maxsize: 1215" https://archive.is/MC0dq
	var maxiters = 3000

	for i := 0; i < len(word); i++ {
		var isFlag = -1
		var that *FrozenTrieNode
		for {
			that = node.getChild(isFlag + 1)
			if !that.flag() {
				break
			}
			isFlag += 1
			if !((isFlag + 1) < node.getChildCount()) {
				break
			}
		}
		var minChild = isFlag
		if debug {
			fmt.Printf("            count: %d i: %d  w: %d  nl: %d  flag: %d\n", node.getChildCount(), i, word[i], node.letter(), isFlag)
		}
		if (node.getChildCount() - 1) <= minChild {
			return false, emptyreturn
		}
		//if(config.compress === true && !config.unroll)
		var high = node.getChildCount()
		var low = isFlag
		var child *FrozenTrieNode
		for (high - low) > 1 {
			var probe = (high + low) / 2
			child = node.getChild(probe)
			var prevchild *FrozenTrieNode

			if probe > isFlag {
				var tmp = node.getChild(probe - 1)
				prevchild = tmp
			} else {
				prevchild = nil
			}

			if debug {
				fmt.Printf("            current: %d l: %d h: %d w: %d\n", child.letter(), low, high, word[i])
				//return false,emptyreturn
			}

			if child.compressed() || (prevchild != nil && (prevchild.compressed() && !prevchild.flag())) {
				var startchild []*FrozenTrieNode
				var endchild []*FrozenTrieNode
				var start = 0
				var end = 0
				startchild = append(startchild, child)
				start = start + 1

				for i := 0; ; i++ {
					if i >= maxiters {
						return false, emptyreturn
					}
					temp := node.getChild(probe - start)
					if !temp.compressed() {
						break
					}
					if temp.flag() {
						break
					}
					startchild = append(startchild, temp)
					start = start + 1
				}
				if debug {
					fmt.Printf("  check: letter : %d  word : %d start: %d\n", startchild[start-1].letter(), word[i], start)
				}

				if uint8(startchild[start-1].letter()) > word[i] {
					if debug {
						fmt.Printf("            shrinkh start: %d s: %d w: %d\n", startchild[start-1].letter(), start, word[i])
					}

					high = probe - start + 1
					if high-low <= 1 {
						if debug {
							fmt.Printf("    (high - low ): %d c: %d h: %d l: %d cl: %d w: %d pr: %d\n", (high - low), node.getChildCount(), high, low, child.letter(), word[i], probe)
						}
						return false, emptyreturn
					}
					continue
				}

				if child.compressed() {
					for i := 0; ; i++ {
						if i >= maxiters {
							return false, emptyreturn
						}
						end = end + 1
						temp := node.getChild(probe + end)
						endchild = append(endchild, temp)
						if !temp.compressed() {
							break
						}
					}
				}

				if uint8(startchild[start-1].letter()) < word[i] {
					if debug {
						fmt.Printf("            shrinkh start: %d s: %d w: %d\n", startchild[start-1].letter(), start, word[i])
					}
					low = probe + end
					if high-low <= 1 {
						if debug {
							fmt.Printf("    (high - low ): %d c: %d h: %d l: %d cl: %d w: %d pr: %d\n", (high - low), node.getChildCount(), high, low, child.letter(), word[i], probe)
						}
						return false, emptyreturn
					}
					continue
				}

				for ii, jj := 0, len(startchild)-1; ii < jj; ii, jj = ii+1, jj-1 {
					startchild[ii], startchild[jj] = startchild[jj], startchild[ii]
				}
				var nodes = startchild
				if endchild != nil || len(endchild) > 0 {
					nodes = append(nodes, endchild...)
				}
				var comp []uint8
				for inc := range nodes {
					comp = append(comp, uint8(nodes[inc].letter()))
				}

				var sliceend = i + len(comp)
				if sliceend > len(word) {
					sliceend = len(word)
				}
				var w = word[i:sliceend]

				if debug {
					fmt.Printf("p: %d comp: %v w: %v\n", probe, comp, w)
				}
				if len(w) < len(comp) {
					return false, emptyreturn
				}
				for inc := range comp {
					if w[inc] != comp[inc] {
						return false, emptyreturn
					}
				}
				child = nodes[len(nodes)-1]
				i += len(comp) - 1
				break
			} else {
				if uint8(child.letter()) == word[i] {
					break
				} else if word[i] > uint8(child.letter()) {
					low = probe
				} else {
					high = probe
				}
			}

			if high-low <= 1 {
				return false, emptyreturn
			}
		}
		if child == nil {
			fmt.Printf("trie: lookup: child is nil %s; i: %d; hi: %d; lo: %d; node: %s",
				word, i, high, low, node)
			return false, emptyreturn
		} else {
			if debug {
				fmt.Printf("        next: %d\n", child.letter())
			}
			node = child
		}
	}
	if node.final() {
		return node.final(), node.value()
	}
	return false, emptyreturn
}

func (ft *FrozenTrie) LoadTag(filepath string) error {
	data, err := os.ReadFile(filepath)
	if err != nil {
		return fmt.Errorf("trie: loadtag read err: %v", err)
	}

	var obj map[string]any
	err = json.Unmarshal(data, &obj)
	if len(obj) <= 0 {
		err = fmt.Errorf("trie: zero len blocklist json")
	}
	if err != nil {
		return fmt.Errorf("trie: loadtag json err: %v", err)
	}
	// FIXME: Change type(rflags) to map
	//FT.rflags = make([]string, len(obj)+1)
	for key := range obj {
		indata := obj[key].(map[string]interface{})
		var index = int(indata["value"].(float64))
		var data = indata["uname"].(string)
		ft.rflags[index] = data

		ft.fdata[key] = indata
	}
	return nil
}

func (ft *FrozenTrie) FlagstoTag(flags []uint32) []string {
	values := []string{}
	if len(ft.rflags) <= 0 || len(flags) <= 0 {
		return values
	}
	// flags has to be an array of 16-bit integers.
	header := uint16(flags[0])
	tagIndices := []int{}
	var mask uint16
	mask = 0x8000
	if debug {
		fmt.Println("trie", ft.rflags)
	}
	for i := range 16 {
		if (header << i) == 0 {
			break
		}
		if (header & mask) == mask {
			tagIndices = append(tagIndices, i)
		}
		mask = mask >> 1
	}
	// flags.length must be equal to tagIndices.length
	if len(tagIndices) != (len(flags) - 1) {
		fmt.Printf("trie: flagstotag: %v %v flags and header mismatch (bug in upsert?)\n", tagIndices, flags)
		return values
	}
	for i := 1; i < len(flags); i++ {
		//fmt.Println("i : ",i)
		var flag = uint16(flags[i])
		var index = tagIndices[i-1]
		mask = 0x8000
		for j := range 16 {
			if (flag << j) == 0 {
				break
			}
			if (flag & mask) == mask {
				var pos = (index * 16) + j
				if debug {
					fmt.Printf("trie: flagstotag: pos %d  index/tagIndices %d %v j/i %d %d\n",
						pos, index, tagIndices, j, i)
				}
				if pos >= len(ft.rflags) {
					if debug {
						fmt.Printf("trie: flagstotag: pos %d out of bounds in len(rflags) %d\n",
							pos, len(ft.rflags))
					}
				} else {
					v := ft.rflags[pos]
					if len(v) > 0 {
						values = append(values, ft.rflags[pos])
					}
				}
			}
			mask = mask >> 1
		}
	}
	return values
}

func (ft *FrozenTrie) DNlookup(dn string, usr_flag string) (bool, []string) {

	if ft.usrFlag == "" || ft.usrFlag != usr_flag {
		if debug {
			fmt.Println("trie: flag saved: ", ft.usrFlag)
		}
		var blocklists []string
		var err error
		s := strings.Split(usr_flag, ":")
		if len(s) > 1 {
			blocklists, err = ft.decode(s[1], s[0])
		} else {
			blocklists, err = ft.decode(usr_flag, "0")
		}
		if err != nil {
			fmt.Println("trie", err, s)
			ft.usrFlag = ""
			ft.usrBlocklists = nil
			return false, nil
		}
		ft.usrBlocklists = blocklists
		ft.usrFlag = usr_flag
	}

	// lookup the whole fqdn, ex: a.b.c.tld
	block, lists := ft.lookupDomain(dn)

	if block {
		return block, lists
	}

	// lookup the subdomains, ex: [b.c.tld, c.tld, tld]
	subs := subdomains(dn)

	for _, d := range subs {
		block, lists = ft.lookupDomain(d)
		if block {
			break
		}
	}

	if !block {
		lists = []string{}
	}

	return block, lists
}

func (ft *FrozenTrie) lookupDomain(dn string) (bool, []string) {
	dn = strings.TrimSpace(dn)
	bvalue := ft.bcache.Get(dn)
	fvalue := ft.fcache.Get(dn)
	var blfname []string
	var retlist []string
	var found = false
	if len(bvalue) > 0 {
		// fmt.Printf("Return frm B-Cache : %s blacklist %s\n", blfname, FT.usr_bl)
		blfname = strings.Split(bvalue, "-")
		found, retlist = dupElems(ft.usrBlocklists, blfname)
		return found, retlist
	}
	if len(fvalue) > 0 {
		//fmt.Println("Return frm F-Cache : ")
		return found, blfname
	}

	var arr = []uint32{}
	var tag = []string{}
	var status bool
	str_uint8, _ := encodeText(dn)
	status, arr = ft.lookup(str_uint8)
	if status && len(arr) > 0 {
		tag = ft.FlagstoTag(arr)

		//fmt.Printf("Return frm lookup and flagtotag : %d\n", len(tag))
		found, retlist = dupElems(ft.usrBlocklists, tag)

		ft.bcache.Set(dn, strings.Join(tag, "-"))
		//fmt.Println("Add to B-Cache lenght : ",*FT.blen)
		return found, retlist
	} else {
		ft.fcache.Set(dn, "")
		//fmt.Println("Add to F-Cache lenght : ",*FT.flen)
		return found, retlist
	}
}

func (ft *FrozenTrie) Lookup(word []uint8) (bool, []uint32) {
	return ft.lookup(word)
}

func (ft *FrozenTrie) CreateUrlEncodedflag(fl []string) string {
	var val = 0
	var res = ""
	for _, flag := range fl {
		indata := ft.fdata[flag].(map[string]any)
		//fmt.Println(indata)
		val = int(indata["value"].(float64))
		//header := 0
		index := (val / 16)
		pos := val % 16
		dataIndex1 := 0
		h := 0
		if len(res) >= 1 {
			h = dec16(res, 0)
		}

		mask := uint16(0)
		if v, ok := MaskLo[16]; ok && len(v) > 16-index && 16-index > 0 {
			mask = v[16-index]
		}
		//fmt.Println("Mask Bottom : ",uint(FT.data.MaskBottom[16][16 - index]))
		dataIndex := countSetBits(h&int(mask)) + 1

		n := 0
		if ((h >> (15 - index)) & 0x1) != 1 {
			n = 0
		} else {
			n = dec16(res, dataIndex)
		}

		upsertData := (n != 0)
		h |= 1 << (15 - index)
		n |= 1 << (15 - pos)

		//fmt.Println(Flag_to_uint(res))
		if len(res) >= 2 {
			dataIndex1 = dataIndex
			if upsertData {
				dataIndex1 = dataIndex1 + 1
			}
			res = chr16(h) + flagSubstr(res, 1, dataIndex) + chr16(n) + flagSubstr(res, dataIndex1, 0)
		} else {
			res = chr16(h) + chr16(n)
		}

		//fmt.Println("h : ",h)
		//fmt.Println("n : ",n)
		//fmt.Println("dataindex : ",dataIndex)
		//fmt.Println("dataindex1 : ",dataIndex1)
		//fmt.Println("Index : ",index)
		//fmt.Println("Pos : ",pos)
	}
	//temp := Flag_to_uint(res)
	//fmt.Println(temp)
	//fmt.Println(FT.FlagstoTag(temp))
	//fmt.Println("base 64 encode string :",b64.StdEncoding.EncodeToString([]byte(res)))
	//fmt.Println("url encode string :" ,url.QueryEscape(b64.StdEncoding.EncodeToString([]byte(res))))
	return url.QueryEscape(b64.StdEncoding.EncodeToString([]byte(res)))
}

func (ft *FrozenTrie) UrlEncodedFlagstrToTags(str string) []string {
	str, _ = url.QueryUnescape(str)
	buf, _ := b64.StdEncoding.DecodeString(str)
	str = string(buf)
	return ft.FlagstoTag(flagstrToUint32(str))
}

func (ft *FrozenTrie) decode(stamp string, ver string) (tags []string, err error) {
	decoder := b64.StdEncoding
	if ver == "0" {
		stamp, err = url.QueryUnescape(stamp)
	} else if ver == "1" {
		stamp, err = url.PathUnescape(stamp)
		decoder = b64.URLEncoding
	} else {
		err = fmt.Errorf("trie: version does not exist: %s", ver)
	}

	if err != nil {
		return nil, err
	}

	buf, err := decoder.DecodeString(stamp)
	if err != nil {
		//fmt.Println("b64", stamp)
		return
	}

	var u16 []uint16
	if ver == "0" {
		u16 = stringtouint(string(buf))
	} else if ver == "1" {
		u16 = bytestouint(buf)
	}
	//fmt.Println("%s %v", ver, u16)
	return ft.flagstotag(u16)
}

func (ft *FrozenTrie) flagstotag(flags []uint16) ([]string, error) {
	// flags has to be an array of 16-bit integers.
	if len(flags) <= 0 {
		err := fmt.Errorf("trie: flagstotag: zero len flags")
		return nil, err
	}
	if len(ft.rflags) <= 0 { // unlikely
		err := fmt.Errorf("trie: flagstotag: unexpected zero len blocklist")
		return nil, err
	}

	// first index always contains the header
	header := uint16(flags[0])
	// store of each big-endian position of set bits in header
	tagIndices := []int{}
	values := []string{}
	// b1000,0000,0000,0000
	mask := uint16(0x8000)

	// read first 16 header bits from msb to lsb
	// and capture indices of set bits in tagIndices
	for i := range 16 {
		if (header << i) == 0 {
			break
		}
		if (header & mask) == mask {
			tagIndices = append(tagIndices, i)
		}
		mask = mask >> 1 // shift to read the next msb bit
	}
	// the number of set bits in header must correspond to total
	// blocklist "flags" excluding the header at position 0
	if len(tagIndices) != (len(flags) - 1) {
		return nil, fmt.Errorf("trie: flagstotag: %v %v flags and header mismatch",
			tagIndices, flags)
	}

	// for all blocklist flags excluding the header
	// figure out the blocklist-ids
	for i := 1; i < len(flags); i++ {
		// 16 blocklists are represented by one flag
		// that is, one bit per blocklist
		var flag = uint16(flags[i])
		// get the index of the current flag in the header
		var index = tagIndices[i-1]
		mask = 0x8000
		// for each of the 16 bits in the flag
		// capture the set bits and calculate
		// its actual decimal value, the blocklist-id
		for j := 0; j < 16; j++ {
			if (flag << j) == 0 {
				break
			}
			if (flag & mask) == mask {
				pos := (index * 16) + j
				// from the decimal value which is its
				// blocklist-id, fetch its metadata
				if pos >= len(ft.rflags) {
					if debug {
						fmt.Printf("trie: flagstotag: pos %d out of bounds in len(rflags) %d\n",
							pos, len(ft.rflags))
					}
				} else {
					values = append(values, ft.rflags[pos])
				}
			}
			mask = mask >> 1
		}
	}
	return values, nil
}

func stringtouint(str string) []uint16 {
	runedata := []rune(str)
	resp := make([]uint16, len(runedata))
	for key, value := range runedata {
		resp[key] = uint16(value)
	}
	return resp
}

func bytestouint(b []byte) []uint16 {
	data := make([]uint16, len(b)/2)
	for i := range data {
		// assuming little endian
		data[i] = binary.LittleEndian.Uint16(b[i*2 : (i+1)*2])
	}
	return data
}

func subdomains(target string) []string {
	c := strings.Count(target, ".")
	l := []string{}
	for range c {
		s := strings.Index(target, ".") + 1
		target = target[s:]
		l = append(l, target)
	}
	return l
}
