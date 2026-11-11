package hcore

import (
	"fmt"
	"net"
	"strings"
	"testing"

	"github.com/sagernet/sing-box/adapter"
	"github.com/stretchr/testify/require"
)

// socks is one SOCKS outbound of a test profile, as a config entry: a proxy that answers nothing,
// so a test that starts it only checks what the core does with the list.
func socks(tag string, port int) string {
	return fmt.Sprintf(`{"type":"socks","tag":%q,"server":"127.0.0.1","server_port":%d}`, tag, port)
}

// profile is the content of a profile that holds the given outbounds, the form the app starts the
// core with.
func profile(outbounds ...string) string {
	return `{"outbounds":[` + strings.Join(outbounds, ",") + `]}`
}

// freeTCPPort returns a port of this host that is free now: a test that opens a listener uses it
// to stay clear of the other tests and of a core the developer runs.
func freeTCPPort(t *testing.T) uint16 {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	defer listener.Close()
	return uint16(listener.Addr().(*net.TCPAddr).Port)
}

// hotReloadSettings returns the settings JSON the app sends: the mixed port of this test's core,
// the log level, no Clash API (a fixed port would clash with whatever else runs on the
// machine) and no system TLS engine: its check runs in the background and writes the database
// while the test's temporary directory is being removed. A test may append its own fields to
// the object.
func hotReloadSettings(t *testing.T, mixedPort uint16, logLevel string) string {
	t.Helper()
	return fmt.Sprintf(`{"log-level":%q,"mixed-port":%d,"enable-tun":false,"enable-clash-api":false,"use-system-tls-engine":false}`, logLevel, mixedPort)
}

// runningOutbound returns the outbound the running core has under tag: what the config builder
// made of a proxy of the profile.
func runningOutbound(t *testing.T, tag string) adapter.Outbound {
	t.Helper()
	outbound, loaded := static.Box().Outbound().Outbound(tag)
	require.True(t, loaded, "the outbound %q is not running", tag)
	return outbound
}
