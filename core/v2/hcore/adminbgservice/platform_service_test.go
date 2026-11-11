package adminbgservice

import (
	"testing"

	"github.com/stretchr/testify/require"
)

// The service runs as root and its control API is authenticated by the secret alone. Without one
// it must refuse to serve: otherwise any local user could start an engine (a TUN that takes the
// host's traffic) and stop the user's VPN.
func TestStartAdminBgGrpcServerRefusesWithoutSecret(t *testing.T) {
	next := &hiddifyNext{}
	server, err := next.StartAdminBgGrpcServer("127.0.0.1:0")
	require.Error(t, err)
	require.Nil(t, server)

	next = &hiddifyNext{secret: "too-short"}
	server, err = next.StartAdminBgGrpcServer("127.0.0.1:0")
	require.Error(t, err)
	require.Nil(t, server)
}
