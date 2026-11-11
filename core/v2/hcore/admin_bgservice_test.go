package hcore

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"

	"github.com/stretchr/testify/require"
)

// The path of the helper the VPN service is installed from comes in the request and is run as
// root, so it must be an executable file that another local user cannot replace.
func TestValidateCliPath(t *testing.T) {
	directory := t.TempDir()
	valid := filepath.Join(directory, "HiddifyCli")
	require.NoError(t, os.WriteFile(valid, []byte("#!/bin/sh\n"), 0o755))

	path, err := validateCliPath(valid)
	require.NoError(t, err)
	absolute, err := filepath.Abs(valid)
	require.NoError(t, err)
	require.Equal(t, absolute, path)

	_, err = validateCliPath("")
	require.Error(t, err, "an empty path")

	_, err = validateCliPath(filepath.Join(directory, "missing"))
	require.Error(t, err, "a path that does not exist")

	_, err = validateCliPath(directory)
	require.Error(t, err, "a directory")

	notExecutable := filepath.Join(directory, "not-executable")
	require.NoError(t, os.WriteFile(notExecutable, []byte("data"), 0o644))
	_, err = validateCliPath(notExecutable)
	require.Error(t, err, "a file that is not executable")

	if runtime.GOOS != "windows" {
		shared := filepath.Join(directory, "shared")
		require.NoError(t, os.Mkdir(shared, 0o777))
		require.NoError(t, os.Chmod(shared, 0o777))
		replaceable := filepath.Join(shared, "HiddifyCli")
		require.NoError(t, os.WriteFile(replaceable, []byte("#!/bin/sh\n"), 0o755))
		_, err = validateCliPath(replaceable)
		require.Error(t, err, "a helper another user can replace")
	}
}
