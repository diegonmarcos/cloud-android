package syntax

import (
	"encoding/hex"
	"testing"

	"github.com/stretchr/testify/require"
)

type ProtocolVersion uint16

type ExtensionType uint16
type Extension struct {
	ExtensionType ExtensionType
	ExtensionData []byte `tls:"head=2"`
}

type Random [32]byte
type CipherSuite uint16

type ClientHello struct {
	LegacyVersion            ProtocolVersion
	Random                   Random
	LegacySessionID          []byte        `tls:"head=1,max=32"`
	CipherSuites             []CipherSuite `tls:"head=2,min=2"`
	LegacyCompressionMethods []byte        `tls:"head=1,min=1"`
	Extensions               []Extension   `tls:"head=2"`
}

type ServerHello struct {
	Version     ProtocolVersion
	Random      Random
	CipherSuite CipherSuite
	Extensions  []Extension `tls:"head=2"`
}

var (
	extValidIn = Extension{
		ExtensionType: ExtensionType(0x000a),
		ExtensionData: []byte{0xf0, 0xf1, 0xf2, 0xf3, 0xf4},
	}
	extEmptyIn = Extension{
		ExtensionType: ExtensionType(0x000a),
		ExtensionData: []byte{},
	}
	extListValidIn  = []Extension{extValidIn, extEmptyIn}
	extListValidHex = "000d000a0005f0f1f2f3f4000a0000"

	helloRandom = [32]byte{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07,
		0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
		0x20, 0x21, 0x22, 0x23, 0x24, 0x25, 0x26, 0x27,
		0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37}
	chValidIn = ClientHello{
		LegacyVersion:            0x0303,
		Random:                   helloRandom,
		LegacySessionID:          []byte{},
		CipherSuites:             []CipherSuite{0x0001, 0x0002, 0x0003},
		LegacyCompressionMethods: []byte{0},
		Extensions:               extListValidIn,
	}
	chValidHex = "0303" + hex.EncodeToString(helloRandom[:]) + "00" +
		"0006000100020003" + "0100" + extListValidHex

	shValidIn = ServerHello{
		Version:     0x7f12,
		Random:      helloRandom,
		CipherSuite: CipherSuite(0x0001),
		Extensions:  extListValidIn,
	}
	shValidHex = "7f12" + hex.EncodeToString(helloRandom[:]) + "0001" + extListValidHex
)

func TestTLSMarshal(t *testing.T) {
	chValid, _ := hex.DecodeString(chValidHex)
	shValid, _ := hex.DecodeString(shValidHex)

	// ClientHello marshal
	out, err := Marshal(chValidIn)
	require.Nil(t, err)
	require.Equal(t, out, chValid)

	// ServerHello marshal
	out, err = Marshal(shValidIn)
	require.Nil(t, err)
	require.Equal(t, out, shValid)
}

func TestTLSUnmarshal(t *testing.T) {
	chValid, _ := hex.DecodeString(chValidHex)
	shValid, _ := hex.DecodeString(shValidHex)

	// ClientHello marshal
	var ch ClientHello
	read, err := Unmarshal(chValid, &ch)
	require.Nil(t, err)
	require.Equal(t, read, len(chValid))
	require.Equal(t, ch, chValidIn)

	// ServerHello marshal
	var sh ServerHello
	read, err = Unmarshal(shValid, &sh)
	require.Nil(t, err)
	require.Equal(t, read, len(shValid))
	require.Equal(t, sh, shValidIn)
}
