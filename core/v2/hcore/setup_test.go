package hcore

import (
	"errors"
	"testing"

	"github.com/hiddify/hiddify-core/v2/hcommon"
	"github.com/stretchr/testify/require"
)

// The Setup RPC answers a success with no message. It used to call err.Error() on a nil error,
// which panicked in the handler and took the core down with it.
func TestSetupResponse(t *testing.T) {
	response, err := setupResponse(nil)
	require.NoError(t, err)
	require.Equal(t, hcommon.ResponseCode_OK, response.Code)
	require.Empty(t, response.Message)

	cause := errors.New("listen on 127.0.0.1:17078: address already in use")
	response, err = setupResponse(cause)
	require.ErrorIs(t, err, cause)
	require.Equal(t, hcommon.ResponseCode_FAILED, response.Code)
	require.Equal(t, cause.Error(), response.Message)
}
