package hcore

import (
	"context"

	"github.com/hiddify/hiddify-core/v2/hcommon"
	"github.com/hiddify/hiddify-core/v2/service_manager"
)

var (
	sWorkingPath          string
	sTempPath             string
	sUserID               int
	sGroupID              int
	statusPropagationPort int64
	sSetupMode            SetupMode // which core this process is: the app's own, or the background one
)

func InitHiddifyService() error {
	return service_manager.StartServices()
}

func (s *CoreService) Setup(ctx context.Context, req *SetupRequest) (*hcommon.Response, error) {
	// grpcServer is written under mu by Setup and CloseGrpcServer: reading it without the lock
	// races with them, and a concurrent map read with a write is a fatal error in Go.
	mu.Lock()
	started := grpcServer[req.Mode] != nil
	mu.Unlock()
	if started {
		return setupResponse(nil)
	}
	return setupResponse(Setup(req, nil))
}

// setupResponse is the answer of the Setup RPC: a success carries no message, a failure carries
// the error's text. Split out of the handler so a test can check both without starting a core.
func setupResponse(err error) (*hcommon.Response, error) {
	if err == nil {
		return &hcommon.Response{Code: hcommon.ResponseCode_OK}, nil
	}
	return &hcommon.Response{Code: hcommon.ResponseCode_FAILED, Message: err.Error()}, err
}
