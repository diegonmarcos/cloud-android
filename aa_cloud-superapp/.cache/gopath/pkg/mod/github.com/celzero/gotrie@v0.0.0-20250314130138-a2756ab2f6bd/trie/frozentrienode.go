// Copyright (c) 2025 RethinkDNS and its authors.
//
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package trie

import "fmt"

type FrozenTrieNode struct {
	trie                             *FrozenTrie
	index                            int
	valCached                        *[]uint32
	finCached, comCached, flagCached *bool
	whCached                         *uint32
	fcCached, chCached               *int
}

func (ftn *FrozenTrieNode) String() string {
	return fmt.Sprintf("trie: idx: %d, char: %d, fin? %t, wh: %d, cz? %t, flag? %t, first: %d, next: %d, children: %d, val: %v", ftn.index, ftn.letter(), ftn.final(), ftn.where(), ftn.compressed(), ftn.flag(), ftn.firstChild(), ftn.childOfNextNode(), ftn.childCount(), ftn.value())
}

func NewFrozenTrieNode(ft *FrozenTrie, index int) *FrozenTrieNode {
	ftn := &FrozenTrieNode{
		trie:  ft,
		index: index,
	}
	if debug {
		fmt.Printf("trie: %d :i, fc: %d tl: %d c: %t f: %t wh: %d flag: %t\n", ftn.index, ftn.firstChild(), ftn.letter(), ftn.compressed(), ftn.final(), ftn.where(), ftn.flag())
	}
	return ftn
}

func (ftn *FrozenTrieNode) final() bool {
	if ftn.finCached == nil {
		tmp := (ftn.trie.data.get(ftn.trie.letterStart+(ftn.index*ftn.trie.bitslen)+ftn.trie.extraBit, 1) == 1)
		ftn.finCached = &tmp
	}
	return *ftn.finCached
}

func (ftn *FrozenTrieNode) where() uint32 {
	if ftn.whCached == nil {
		tmp := ftn.trie.data.get(ftn.trie.letterStart+(ftn.index*ftn.trie.bitslen)+1+ftn.trie.extraBit, ftn.trie.bitslen-1-ftn.trie.extraBit)
		ftn.whCached = &tmp
	}
	return *ftn.whCached
}

func (ftn *FrozenTrieNode) compressed() bool {
	if ftn.comCached == nil {
		tmp := (ftn.trie.data.get(ftn.trie.letterStart+(ftn.index*ftn.trie.bitslen), 1) == 1)
		ftn.comCached = &tmp
	}
	return *ftn.comCached
}

func (ftn *FrozenTrieNode) flag() bool {
	if ftn.flagCached == nil {
		tmp := (ftn.compressed() && ftn.final()) //(config.valueNode) ?
		ftn.flagCached = &tmp
	}
	return *ftn.flagCached
}

func (ftn *FrozenTrieNode) letter() uint32 {
	return ftn.where()
}

func (ftn *FrozenTrieNode) firstChild() int {
	if ftn.fcCached == nil {
		tmp := ftn.trie.directory.rank(0, ftn.index+1) - ftn.index
		ftn.fcCached = &tmp
	}
	return *ftn.fcCached
}

func (ftn *FrozenTrieNode) childOfNextNode() int {
	if ftn.chCached == nil {
		tmp := ftn.trie.directory.rank(0, ftn.index+2) - ftn.index - 1
		ftn.chCached = &tmp
	}
	return *ftn.chCached
}

func (ftn *FrozenTrieNode) childCount() int {
	return ftn.childOfNextNode() - ftn.firstChild()
}

func (ftn *FrozenTrieNode) value() []uint32 {
	if ftn.valCached == nil {
		//let valueChain = this;
		value := []uint32{}
		i := 0
		j := 0
		//if (config.debug) console.log("thisnode: index/vc/ccount ", this.index, this.letter(), this.childCount())
		for i < ftn.childCount() {
			valueChain := ftn.getChild(i)
			//if (config.debug) console.log("vc no-flag end vlet/vflag/vindex/val ", i, valueChain.letter(), valueChain.flag(), valueChain.index, value)
			if !valueChain.flag() {
				break
			}
			if i%2 == 0 {
				value = append(value, valueChain.letter()<<8)
			} else {
				value[j] = (value[j] | valueChain.letter())
				j += 1
			}
			i += 1
		}
		ftn.valCached = &value
	}

	return *ftn.valCached
}

func (ftn *FrozenTrieNode) getChildCount() int {
	return ftn.childCount()
}

func (ftn *FrozenTrieNode) getChild(index int) *FrozenTrieNode {
	return ftn.trie.getNodeByIndex(ftn.firstChild() + index)
}
