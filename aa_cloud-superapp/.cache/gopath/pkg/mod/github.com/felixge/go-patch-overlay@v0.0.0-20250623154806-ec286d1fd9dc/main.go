package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"io/ioutil"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/bluekeyes/go-gitdiff/gitdiff"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func run() error {
	overlayDir := flag.String("overlay", "", "Directory for overlay, defaults to a new temporary directory")
	goPath := flag.String("go", "go", "Path to Go toolchain")
	flag.Parse()
	o := Overlay{Patches: flag.Args(), OverlayDir: *overlayDir, GoPath: *goPath}
	jsonPath, err := o.Generate()
	if err != nil {
		return err
	}
	fmt.Printf("%s\n", jsonPath)
	return nil
}

type Overlay struct {
	Patches    []string
	OverlayDir string
	GoPath     string
	Goroot     string
}

type overlayJSON struct {
	Replace map[string]string
}

func (o Overlay) Generate() (string, error) {
	j := overlayJSON{Replace: map[string]string{}}
	if err := o.generate(&j); err != nil {
		return "", err
	}
	jsonPath := filepath.Join(o.OverlayDir, "overlay.json")
	jsonData, err := json.MarshalIndent(j, "", "  ")
	if err != nil {
		return "", err
	}
	err = ioutil.WriteFile(jsonPath, jsonData, 0644)
	return jsonPath, err
}

func (o *Overlay) resolveGOROOT() error {
	if o.Goroot != "" {
		return nil
	}
	if o.GoPath == "" {
		o.GoPath = "go"
	}
	cmd := exec.Command(o.GoPath, "env", "GOROOT")
	b, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("resolving GOROOT: %w", err)
	}
	o.Goroot = strings.TrimSpace(string(b))
	return nil
}

func (o *Overlay) generate(j *overlayJSON) error {
	if o.OverlayDir == "" {
		tmpDir, err := ioutil.TempDir("", "go-patch-overlay")
		if err != nil {
			return err
		}
		o.OverlayDir = tmpDir
	}

	var err error
	o.OverlayDir, err = filepath.Abs(o.OverlayDir)
	if err != nil {
		return err
	}

	if err := o.resolveGOROOT(); err != nil {
		return err
	}

	if err := os.RemoveAll(o.OverlayDir); err != nil {
		return err
	}

	for _, patch := range o.Patches {
		if err := o.applyPatch(patch, j); err != nil {
			return err
		}
	}
	return nil
}

func (o Overlay) applyPatch(pathPath string, j *overlayJSON) error {
	patchData, err := ioutil.ReadFile(pathPath)
	if err != nil {
		return err
	}
	files, _, err := gitdiff.Parse(bytes.NewReader(patchData))
	if err != nil {
		return err
	}
	for _, file := range files {
		srcPath := filepath.Join(o.Goroot, file.OldName)
		if file.NewName == "" {
			// Having an empty string as the replacement path in the
			// overlay tells the compiler the file doesn't exist
			j.Replace[srcPath] = ""
			continue
		}
		overlayPath := filepath.Join(o.OverlayDir, file.NewName)
		if err := os.MkdirAll(filepath.Dir(overlayPath), 0755); err != nil {
			return fmt.Errorf("making overlay path: %w", err)
		}
		if file.OldName == "" {
			// This is a new file. We still want to give it a
			// "source" path in the overlay so the compiler knows
			// it's there
			srcPath = filepath.Join(o.Goroot, file.NewName)
		} else if _, err := os.Stat(overlayPath); os.IsNotExist(err) {
			if err := copyFile(srcPath, overlayPath); err != nil {
				return fmt.Errorf("copying %s to %s: %s", srcPath, overlayPath, err)
			}
		} else if err != nil {
			return fmt.Errorf("stat %s: %s", overlayPath, err)
		}

		var beforeData []byte
		if file.OldName != "" {
			beforeData, err = ioutil.ReadFile(overlayPath)
			if err != nil {
				return fmt.Errorf("reading %s: %s", overlayPath, err)
			}
		}
		afterData := &bytes.Buffer{}
		if err := gitdiff.NewApplier(bytes.NewReader(beforeData)).ApplyFile(afterData, file); err != nil {
			return err
		}
		if err := ioutil.WriteFile(overlayPath, afterData.Bytes(), 0644); err != nil {
			return fmt.Errorf("writing %s: %s", overlayPath, err)
		}
		j.Replace[srcPath] = overlayPath
	}
	return nil
}

func copyFile(src, dst string) error {
	input, err := ioutil.ReadFile(src)
	if err != nil {
		return err
	}
	return ioutil.WriteFile(dst, input, 0644)
}
