package syntax

import (
	"reflect"
	"runtime"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestTagParsing(t *testing.T) {
	cases := []struct {
		encoded string
		opts    fieldOptions
	}{
		{
			encoded: "head=2,min=3,max=60000",
			opts: fieldOptions{
				headerSize: 2,
				minSize:    3,
				maxSize:    60000,
			},
		},
		{
			encoded: "head=varint,min=3,max=60000",
			opts: fieldOptions{
				varintHeader: true,
				minSize:      3,
				maxSize:      60000,
			},
		},
		{
			encoded: "head=none,min=3,max=60000",
			opts: fieldOptions{
				omitHeader: true,
				minSize:    3,
				maxSize:    60000,
			},
		},
		{
			encoded: "varint",
			opts:    fieldOptions{varint: true},
		},
		{
			encoded: "optional",
			opts:    fieldOptions{optional: true},
		},
		{
			encoded: "omit",
			opts:    fieldOptions{omit: true},
		},
	}

	for _, c := range cases {
		parsed := parseTag(c.encoded)
		require.Equal(t, parsed, c.opts)
	}
}

func TestTagConsistency(t *testing.T) {
	cases := []string{
		"head=3,head=none",
		"head=none,head=varint",
		"head=varint,head=3",
		"min=4,max=2",
		"head=3,varint",
		"varint,optional",
		"optional,head=3",
		"omit,varint",
	}

	tryToParse := func(opts string) (err error) {
		defer func() {
			if r := recover(); r != nil {
				if _, ok := r.(runtime.Error); ok {
					panic(r)
				}
				if s, ok := r.(string); ok {
					panic(s)
				}
				err = r.(error)
			}
		}()
		parseTag(opts)
		return nil
	}

	for _, opts := range cases {
		err := tryToParse(opts)
		require.NotNil(t, err)
	}
}

func TestTagValidity(t *testing.T) {
	sliceTags := parseTag("head=2")
	uintTags := parseTag("varint")
	ptrTags := parseTag("optional")

	sliceType := reflect.TypeOf([]byte{})
	uintType := reflect.TypeOf(uint8(0))
	ptrType := reflect.TypeOf(new(uint8))

	require.True(t, sliceTags.ValidForType(sliceType))
	require.True(t, uintTags.ValidForType(uintType))
	require.True(t, ptrTags.ValidForType(ptrType))

	require.False(t, uintTags.ValidForType(sliceType))
	require.False(t, ptrTags.ValidForType(uintType))
	require.False(t, sliceTags.ValidForType(ptrType))
}
