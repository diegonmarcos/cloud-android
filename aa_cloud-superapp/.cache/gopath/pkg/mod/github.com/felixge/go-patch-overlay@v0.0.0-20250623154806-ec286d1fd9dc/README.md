# go-patch-overlay

An experimental way to apply patches to the Go runtime at build time.

Assuming you have a [patch](./example/goroutineid/patches/getgid.patch) to apply to the Go source tree, you can use it like this:

```
$ go build -overlay="$(go-patch-overlay getgid.patch)"
```

This will work for patches aimed at the runtime or stdlib. It won't work for the compiler/linker.
You can provide multiple patches. The patches will be applied in the order they're provided.
The patches will applied to the sources in the GOROOT directory of the Go toolchain.
This should be the same toolchain you'll use to build the program.
The toolchain defaults to the current "go" command.
Use the `-go` argument to point to a different toolchain.
