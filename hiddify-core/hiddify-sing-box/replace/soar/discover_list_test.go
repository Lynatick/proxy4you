package soar

import (
	"strings"
	"testing"
)

func TestDiscoveryList(t *testing.T) {
	d := Discovery{Country: "IR", Candidates: []string{"192.0.2.1"}}
	list := d.List()
	if len(list) < 10 || list[0] != "192.0.2.1:53" {
		t.Fatalf("list: %v", list[:min(len(list), 3)])
	}
	seen := map[string]bool{}
	for _, r := range list {
		if seen[r] {
			t.Fatalf("%s twice", r)
		}
		seen[r] = true
	}
	if !seen["1.1.1.1:53"] {
		t.Fatal("no global resolver")
	}
	if n := len(Discovery{Country: "IR", SampleRanges: 20}.List()); n < len(Discovery{Country: "IR"}.List())+20 {
		t.Fatalf("sampled: %d", n)
	}
}

func TestDiscoveryListUnmapsIPv4MappedResolvers(t *testing.T) {
	saved := systemResolvers
	t.Cleanup(func() { systemResolvers = saved })
	// macOS's /etc/resolv.conf lists each nameserver as IPv4 and as IPv4-mapped IPv6.
	systemResolvers = func() []string {
		return []string{"127.0.2.2:53", "[::ffff:127.0.2.2]:53", "[::ffff:127.0.2.3]:53"}
	}
	list := Discovery{Candidates: []string{"::ffff:192.0.2.1"}}.List()
	seen := map[string]bool{}
	for _, r := range list {
		if seen[r] {
			t.Fatalf("%s twice", r)
		}
		seen[r] = true
	}
	for _, want := range []string{"192.0.2.1:53", "127.0.2.2:53", "127.0.2.3:53"} {
		if !seen[want] {
			t.Fatalf("no %s in %v", want, list[:min(len(list), 5)])
		}
	}
	for r := range seen {
		if strings.HasPrefix(r, "[::ffff:") {
			t.Fatalf("mapped address kept: %s", r)
		}
	}
}
