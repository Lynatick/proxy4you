package config

import (
	"encoding/json"
	"testing"

	"github.com/sagernet/sing-box/option"
)

// oneProxyProfile is the content of a profile with a single proxy: a config the builder accepts,
// with no chain, so the tests around it only see what the settings make of one proxy.
const oneProxyProfile = `{"outbounds":[
	{"type":"socks","tag":"proxy-1","server":"127.0.0.1","server_port":1080}
]}`

// chainTestProfile is the profile the chain and DNS tests build: two proxies a detour can run
// through, and no chain hop of its own.
const chainTestProfile = `{"outbounds":[
	{"type":"socks","tag":"proxy-a","server":"127.0.0.1","server_port":1080},
	{"type":"socks","tag":"proxy-b","server":"127.0.0.1","server_port":1081}
]}`

// chainTestOptions returns the default options with json (the app's settings) applied on top of
// them. A settings value that is absent keeps its default.
func chainTestOptions(t *testing.T, jsonSettings string) *HiddifyOptions {
	t.Helper()
	options := DefaultHiddifyOptions()
	if jsonSettings == "" {
		return options
	}
	if err := json.Unmarshal([]byte(jsonSettings), options); err != nil {
		t.Fatalf("reading the test settings: %v", err)
	}
	return options
}

// selectorItems returns the members of the selector the app connects through, in its order: what
// the proxies page and a keep-top pick read.
func selectorItems(options *option.Options) []string {
	if options == nil {
		return nil
	}
	for _, outbound := range options.Outbounds {
		if outbound.Tag != OutboundSelectTag {
			continue
		}
		if selector, ok := outbound.Options.(*option.SelectorOutboundOptions); ok {
			return selector.Outbounds
		}
	}
	return nil
}
