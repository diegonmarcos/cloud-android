package main

import (
	"bytes"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/tools/txtar"
)

func Test(t *testing.T) {
	files, err := filepath.Glob("testdata/*.txtar")
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		t.Run(filepath.Base(f), func(t *testing.T) { testPatches(t, f) })
	}
}

func testPatches(t *testing.T, archive string) {
	dir := t.TempDir()

	var (
		toolchain string
		output    []byte
		patches   []string
		sources   []string
	)

	ar, err := txtar.ParseFile(archive)
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range ar.Files {
		if f.Name == "toolchain" {
			toolchain = strings.TrimSpace(string(f.Data))
		}
		if f.Name == "output.txt" {
			output = f.Data
		}
		if strings.HasSuffix(f.Name, ".patch") {
			patches = append(patches, filepath.Join(dir, f.Name))
		}
		if strings.HasSuffix(f.Name, ".go") {
			sources = append(sources, filepath.Join(dir, f.Name))
		}
	}
	if toolchain == "" {
		t.Fatalf("%s did not specify a toolchain", archive)
	}
	if len(sources) == 0 {
		t.Fatalf("no Go sources")
	}
	if len(patches) == 0 {
		t.Fatalf("no patches")
	}

	fs, err := txtar.FS(ar)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.CopyFS(dir, fs); err != nil {
		t.Fatal(err)
	}

	overlayDir := filepath.Join(dir, "overlay")
	os.Mkdir(overlayDir, 0777)
	o := Overlay{
		OverlayDir: overlayDir,
		GoPath:     toolchain,
		Patches:    patches,
	}

	overlay, err := o.Generate()
	if err != nil {
		t.Fatal(err)
	}

	binary := filepath.Join(dir, "testprog")
	cmd := exec.Command(toolchain,
		"build",
		"-overlay", overlay,
		"-o", binary,
	)
	cmd.Args = append(cmd.Args, sources...)
	b, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("building test program: %s, output:\n%s", err, b)
	}

	cmd = exec.Command(binary)
	b, err = cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("building test program: %s, output:\n%s", err, b)
	}
	if !bytes.Equal(output, b) {
		t.Fatalf("wanted output:\n%s\ngot output: %s", output, b)
	}
}
