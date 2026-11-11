package ray2sing

import (
	"testing"

	T "github.com/sagernet/sing-box/option"
)

// The insecure flag of a share link used to be read three different ways: `!= "0"` (so
// insecure=false meant on), `== "1"` (so insecure=true meant off), and `== "true" || == "1"`.
// Only the explicit forms now count, and everything else means verification stays on.
func TestShareLinkInsecureFlag(t *testing.T) {
	parse := map[string]func(string) (*T.Outbound, error){
		"http":      HttpSingbox,
		"https":     HttpsSingbox,
		"hysteria":  HysteriaSingbox,
		"hysteria2": Hysteria2Singbox,
		"tuic":      TuicSingbox,
		"vless":     VlessSingbox,
		"anytls":    AnyTLSSingbox,
	}
	links := map[string]string{
		"http":      "http://user:pass@1.2.3.4:8080?tls=true&insecure=%s",
		"https":     "https://user:pass@1.2.3.4:443?insecure=%s",
		"hysteria":  "hysteria://1.2.3.4:443?peer=a.com&upmbps=10&downmbps=100&insecure=%s",
		"hysteria2": "hysteria2://pass@1.2.3.4:443?sni=a.com&insecure=%s",
		"tuic":      "tuic://uuid:pass@1.2.3.4:443?sni=a.com&insecure=%s",
		"vless":     "vless://uuid@1.2.3.4:443?security=tls&sni=a.com&insecure=%s",
		"anytls":    "anytls://pass@1.2.3.4:443?sni=a.com&insecure=%s",
	}
	off := []string{"0", "false", "no", "off", ""}
	on := []string{"1", "true", "yes", "on"}
	for scheme, template := range links {
		parser := parse[scheme]
		for _, value := range off {
			out, err := parser(formatLink(template, value))
			if err != nil {
				t.Fatalf("%s insecure=%q: %v", scheme, value, err)
			}
			if tlsInsecure(t, out) {
				t.Errorf("%s insecure=%q: certificate verification is off", scheme, value)
			}
		}
		for _, value := range on {
			out, err := parser(formatLink(template, value))
			if err != nil {
				t.Fatalf("%s insecure=%q: %v", scheme, value, err)
			}
			if !tlsInsecure(t, out) {
				t.Errorf("%s insecure=%q: certificate verification is on", scheme, value)
			}
		}
	}
}

func formatLink(template, value string) string {
	if value == "" {
		return replaceOnce(template, "&insecure=%s", "")
	}
	return replaceOnce(template, "%s", value)
}

func replaceOnce(s, old, new string) string {
	for i := 0; i+len(old) <= len(s); i++ {
		if s[i:i+len(old)] == old {
			return s[:i] + new + s[i+len(old):]
		}
	}
	return s
}

func tlsInsecure(t *testing.T, out *T.Outbound) bool {
	t.Helper()
	switch options := out.Options.(type) {
	case *T.HTTPOutboundOptions:
		return options.TLS.Insecure
	case *T.HysteriaOutboundOptions:
		return options.TLS.Insecure
	case *T.Hysteria2OutboundOptions:
		return options.TLS.Insecure
	case *T.TUICOutboundOptions:
		return options.TLS.Insecure
	case *T.VLESSOutboundOptions:
		return options.TLS.Insecure
	case *T.AnyTLSOutboundOptions:
		return options.TLS.Insecure
	default:
		t.Fatalf("unexpected outbound options %T", out.Options)
		return false
	}
}
