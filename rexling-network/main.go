// Package main provides the app-private Tailscale and SSH byte transports.
package main

/*
#include <stdlib.h>
*/
import "C"

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"time"
	"unsafe"

	"golang.org/x/crypto/ssh"
	"golang.org/x/sys/unix"
	"tailscale.com/net/netmon"
	"tailscale.com/tsnet"
)

type sshCredential struct {
	Key string `json:"key"`
}
type configuration struct {
	Suspended  bool                     `json:"built_in_suspended"`
	Enabled    bool                     `json:"built_in_enabled"`
	Routes     map[string]string        `json:"routes"`
	BuiltIn    bool                     `json:"built_in"`
	ControlURL string                   `json:"control_url"`
	Hostname   string                   `json:"hostname"`
	Directory  string                   `json:"directory"`
	SSH        map[string]sshCredential `json:"ssh"`
	Trusted    map[string]string        `json:"trusted"`
}

func (c configuration) tailscaleEnabled() bool {
	if c.Suspended {
		return false
	}
	if c.BuiltIn || c.Enabled {
		return true
	}
	for _, route := range c.Routes {
		if route == "builtin" {
			return true
		}
	}
	return false
}
func (c configuration) builtInFor(endpoint string) bool {
	if u, err := url.Parse(endpoint); err == nil && u.Path == "/" {
		u.Path = ""
		endpoint = u.String()
	}
	switch c.Routes[endpoint] {
	case "builtin":
		return true
	case "phone":
		return false
	}
	return c.BuiltIn
}

type interfaceInfo struct {
	Name      string   `json:"name"`
	Index     int      `json:"index"`
	MTU       int      `json:"mtu"`
	Flags     int      `json:"flags"`
	Addresses []string `json:"addresses"`
}
type hostPrompt struct {
	Host        string `json:"host"`
	Fingerprint string `json:"fingerprint"`
	Previous    string `json:"previous"`
	Type        string `json:"type"`
}

var state = struct {
	sync.Mutex
	config     configuration
	interfaces []interfaceInfo
	prompts    map[string]hostPrompt
	banners    map[string]string
	clients    map[string]*ssh.Client
	generation uint64
}{prompts: map[string]hostPrompt{}, banners: map[string]string{}, clients: map[string]*ssh.Client{}}
var tail = struct {
	sync.Mutex
	server *tsnet.Server
}{}

func init() {
	if runtime.GOOS == "android" {
		// java.net.NetworkInterface uses Android's permitted interface discovery path.
		netmon.RegisterInterfaceGetter(func() ([]netmon.Interface, error) {
			state.Lock()
			snapshot := append([]interfaceInfo(nil), state.interfaces...)
			state.Unlock()
			result := make([]netmon.Interface, 0, len(snapshot))
			for _, entry := range snapshot {
				item := netmon.Interface{Interface: &net.Interface{Name: entry.Name, Index: entry.Index, MTU: entry.MTU, Flags: net.Flags(entry.Flags)}}
				for _, address := range entry.Addresses {
					_, prefix, err := net.ParseCIDR(address)
					if err == nil {
						item.AltAddrs = append(item.AltAddrs, prefix)
					}
				}
				result = append(result, item)
			}
			return result, nil
		})
	}
}

var configureMutex sync.Mutex

func configure(next configuration) error {
	configureMutex.Lock()
	defer configureMutex.Unlock()
	if next.Directory == "" {
		return errors.New("network state directory is missing")
	}
	if next.ControlURL != "" {
		u, err := url.Parse(next.ControlURL)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Host == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" {
			return errors.New("enter a complete control server HTTP(S) URL")
		}
	}
	if runtime.GOOS == "android" {
		logs := filepath.Join(next.Directory, "logs")
		if err := os.MkdirAll(logs, 0700); err != nil {
			return err
		}
		if err := os.Setenv("TS_LOGS_DIR", logs); err != nil {
			return err
		}
	}
	state.Lock()
	previous := state.config
	state.config = next
	state.generation++
	clients := state.clients
	state.clients = map[string]*ssh.Client{}
	state.prompts = map[string]hostPrompt{}
	state.banners = map[string]string{}
	state.Unlock()
	for _, client := range clients {
		client.Close()
	}
	if previous.tailscaleEnabled() != next.tailscaleEnabled() || previous.ControlURL != next.ControlURL || previous.Hostname != next.Hostname || previous.Directory != next.Directory {
		tail.Lock()
		old := tail.server
		tail.server = nil
		tail.Unlock()
		if old != nil {
			old.Close()
		}
	}
	if next.tailscaleEnabled() {
		go func() { _, _ = tailServer() }()
	}
	return nil
}

// tailHostname is the device name the built-in Tailscale node presents, defaulting to rexling-android.
func tailHostname(config configuration) string {
	if name := strings.TrimSpace(config.Hostname); name != "" {
		return name
	}
	return "rexling-android"
}
func tailServer() (*tsnet.Server, error) {
	tail.Lock()
	defer tail.Unlock()
	if tail.server != nil {
		return tail.server, nil
	}
	state.Lock()
	config := state.config
	state.Unlock()
	if !config.tailscaleEnabled() {
		return nil, errors.New("built-in Tailscale is off")
	}
	control := config.ControlURL
	if control == "" {
		control = "https://controlplane.tailscale.com"
	}
	sum := sha256.Sum256([]byte(control))
	// The state directory depends only on the control server, so renaming keeps the node's identity
	// and sign-in; tsnet re-sends the hostname to control on every start.
	server := &tsnet.Server{Dir: filepath.Join(config.Directory, hex.EncodeToString(sum[:16])), Hostname: tailHostname(config), ControlURL: config.ControlURL,
		Logf: func(string, ...any) {}, UserLogf: func(string, ...any) {}}
	if err := server.Start(); err != nil {
		server.Close()
		return nil, err
	}
	tail.server = server
	return server, nil
}
func networkDial(ctx context.Context, endpoint string, address string) (net.Conn, error) {
	state.Lock()
	builtIn := state.config.builtInFor(endpoint)
	state.Unlock()
	return dialNetwork(ctx, builtIn, address)
}

func dialNetwork(ctx context.Context, builtIn bool, address string) (net.Conn, error) {
	if builtIn {
		server, err := tailServer()
		if err != nil {
			return nil, err
		}
		return server.Dial(ctx, "tcp", address)
	}
	return (&net.Dialer{KeepAlive: 20 * time.Second}).DialContext(ctx, "tcp", address)
}

// Cancelling unfinished enrollment must not reset SSH clients or other routes.
func cancelLogin() error {
	tail.Lock()
	defer tail.Unlock()
	server := tail.server
	if server != nil {
		local, err := server.LocalClient()
		if err != nil {
			return err
		}
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		current, err := local.Status(ctx)
		if err != nil {
			cancel()
			return err
		}
		if current.BackendState == "Running" {
			cancel()
			return errors.New("Tailscale is already connected; sign-in was not cancelled")
		}
		// The local logout clears the pending interactive login before shutdown.
		err = local.Logout(ctx)
		cancel()
		if err != nil {
			return err
		}
	}
	state.Lock()
	state.config.Suspended = true
	state.Unlock()
	tail.server = nil
	if server != nil {
		server.Close()
	}
	return nil
}
func hostIdentity(u *url.URL) string {
	port := u.Port()
	if port == "" {
		port = "22"
	}
	return net.JoinHostPort(strings.ToLower(u.Hostname()), port)
}
func sshClient(ctx context.Context, u *url.URL) (*ssh.Client, error) {
	endpoint := u.String()
	state.Lock()
	cached := state.clients[endpoint]
	credential := state.config.SSH[endpoint]
	generation := state.generation
	state.Unlock()
	if cached != nil {
		return cached, nil
	}
	identity := hostIdentity(u)
	hostKey := func(_ string, _ net.Addr, key ssh.PublicKey) error {
		fingerprint := ssh.FingerprintSHA256(key)
		state.Lock()
		defer state.Unlock()
		trusted := state.config.Trusted[identity]
		if trusted == fingerprint {
			delete(state.prompts, identity)
			return nil
		}
		state.prompts[identity] = hostPrompt{Host: identity, Fingerprint: fingerprint, Previous: trusted, Type: key.Type()}
		return errors.New("review the SSH host fingerprint in Rexling")
	}
	banner := func(message string) error {
		state.Lock()
		state.banners[endpoint] = message
		state.Unlock()
		return nil
	}
	client, err := openSSH(ctx, u, credential.Key, hostKey, banner, func(ctx context.Context, address string) (net.Conn, error) {
		return networkDial(ctx, endpoint, address)
	})
	if err != nil {
		return nil, err
	}
	state.Lock()
	if generation != state.generation {
		state.Unlock()
		client.Close()
		return nil, errors.New("SSH settings changed")
	}
	if existing := state.clients[endpoint]; existing != nil {
		state.Unlock()
		client.Close()
		return existing, nil
	}
	state.clients[endpoint] = client
	delete(state.banners, endpoint)
	state.Unlock()
	return client, nil
}
func openSSH(ctx context.Context, u *url.URL, key string, hostKey ssh.HostKeyCallback, banner ssh.BannerCallback,
	dial func(context.Context, string) (net.Conn, error)) (*ssh.Client, error) {
	user := ""
	if u.User != nil {
		user = u.User.Username()
	}
	if user == "" {
		return nil, errors.New("include the SSH username, such as ssh://alice@host")
	}
	config := &ssh.ClientConfig{User: user, Timeout: 20 * time.Second}
	identity := hostIdentity(u)
	config.HostKeyCallback = hostKey
	config.BannerCallback = banner
	if key != "" {
		signer, err := ssh.ParsePrivateKey([]byte(key))
		if err != nil {
			return nil, errors.New("could not read the saved SSH key")
		}
		config.Auth = []ssh.AuthMethod{ssh.PublicKeys(signer)}
	}
	connection, err := dial(ctx, identity)
	if err != nil {
		return nil, err
	}
	deadline := time.Now().Add(25 * time.Second)
	if limit, ok := ctx.Deadline(); ok && limit.Before(deadline) {
		deadline = limit
	}
	connection.SetDeadline(deadline)
	transport, channels, requests, err := ssh.NewClientConn(connection, identity, config)
	if err != nil {
		connection.Close()
		return nil, fmt.Errorf("SSH login failed: %w", err)
	}
	connection.SetDeadline(time.Time{})
	return ssh.NewClient(transport, channels, requests), nil
}
func rexSocket(ctx context.Context, client *ssh.Client, u *url.URL, probe bool) (string, error) {
	if u.Path != "" && u.Path != "/" {
		return u.Path, nil
	}
	session, err := client.NewSession()
	if err != nil {
		return "", err
	}
	defer session.Close()
	stop := context.AfterFunc(ctx, func() { session.Close() })
	defer stop()
	// Match Rex's CLI discovery. No user text is interpolated into the command.
	const locate = `sh -c 'if command -v rex >/dev/null 2>&1; then r=rex; elif test -x "$HOME/.local/bin/rex"; then r="$HOME/.local/bin/rex"; else echo "Rex is not installed on this SSH host" >&2; exit 127; fi; `
	command := locate + `case "${REX_AUTOSTART-}" in ""|1|t|T|true|TRUE|True) "$r" server start --json;; *) "$r" server status --json;; esac'`
	if probe {
		command = locate + `"$r" --autostart=false server status --json'`
	}
	output, err := session.Output(command)
	if err != nil {
		if probe {
			return "", errors.New("Rex is not running on this SSH host")
		}
		return "", errors.New("could not find or start Rex over SSH; install Rex on the host")
	}
	var status struct {
		Endpoint string `json:"endpoint"`
	}
	if json.Unmarshal(output, &status) != nil {
		return "", errors.New("Rex returned an invalid server status")
	}
	remote, err := url.Parse(status.Endpoint)
	if err != nil || remote.Scheme != "unix" || remote.Path == "" {
		return "", errors.New("this SSH host does not expose a Unix-socket Rex server")
	}
	return remote.Path, nil
}
func dial(ctx context.Context, endpoint string) (net.Conn, error) {
	u, err := url.Parse(endpoint)
	if err != nil {
		return nil, err
	}
	if u.Scheme == "ssh" {
		client, err := sshClient(ctx, u)
		if err != nil {
			return nil, err
		}
		path, err := rexSocket(ctx, client, u, false)
		if err == nil {
			var conn net.Conn
			conn, err = client.DialContext(ctx, "unix", path)
			if err == nil {
				return conn, nil
			}
		}
		state.Lock()
		if state.clients[endpoint] == client {
			delete(state.clients, endpoint)
		}
		state.Unlock()
		client.Close()
		return nil, err
	}
	return networkDial(ctx, endpoint, serverAddress(u))
}

func serverAddress(u *url.URL) string {
	port := u.Port()
	if port == "" {
		if u.Scheme == "https" || u.Scheme == "wss" {
			port = "443"
		} else {
			port = "80"
		}
	}
	return net.JoinHostPort(u.Hostname(), port)
}

type probeOptions struct {
	BuiltIn bool   `json:"builtin"`
	Key     string `json:"key"`
}

type probeSSHConn struct {
	net.Conn
	client *ssh.Client
}

func (c probeSSHConn) Close() error {
	err := c.Conn.Close()
	c.client.Close()
	return err
}

func dialProbe(ctx context.Context, endpoint string, options probeOptions) (net.Conn, error) {
	u, err := url.Parse(endpoint)
	if err != nil {
		return nil, err
	}
	if u.Scheme != "ssh" {
		return dialNetwork(ctx, options.BuiltIn, serverAddress(u))
	}
	identity := hostIdentity(u)
	state.Lock()
	trusted := state.config.Trusted[identity]
	state.Unlock()
	// Only saved host trust is used. A draft never creates a trust prompt or a cached client.
	if trusted == "" {
		return nil, errors.New("verify this SSH host when connecting")
	}
	client, err := openSSH(ctx, u, options.Key, func(_ string, _ net.Addr, key ssh.PublicKey) error {
		if ssh.FingerprintSHA256(key) != trusted {
			return errors.New("verify this SSH host when connecting")
		}
		return nil
	}, nil, func(ctx context.Context, address string) (net.Conn, error) {
		return dialNetwork(ctx, options.BuiltIn, address)
	})
	if err != nil {
		return nil, err
	}
	stop := context.AfterFunc(ctx, func() { client.Close() })
	defer stop()
	path, err := rexSocket(ctx, client, u, true)
	if err == nil {
		conn, dialErr := client.DialContext(ctx, "unix", path)
		if dialErr == nil {
			return probeSSHConn{conn, client}, nil
		}
		err = dialErr
	}
	client.Close()
	return nil, err
}

func status() map[string]any {
	state.Lock()
	builtIn := state.config.tailscaleEnabled()
	prompts := make([]hostPrompt, 0, len(state.prompts))
	for _, p := range state.prompts {
		prompts = append(prompts, p)
	}
	banners := make(map[string]string, len(state.banners))
	for k, v := range state.banners {
		banners[k] = v
	}
	state.Unlock()
	value := map[string]any{"built_in": builtIn, "hosts": prompts, "banners": banners}
	if builtIn {
		server, err := tailServer()
		if err != nil {
			value["error"] = err.Error()
			return value
		}
		local, err := server.LocalClient()
		if err != nil {
			value["error"] = err.Error()
			return value
		}
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		current, err := local.Status(ctx)
		if err != nil {
			value["error"] = err.Error()
		} else {
			value["tailscale"] = current
		}
	}
	return value
}

func importSSHKey(text, passphrase string) (map[string]any, error) {
	var key any
	var err error
	if passphrase == "" {
		key, err = ssh.ParseRawPrivateKey([]byte(text))
	} else {
		key, err = ssh.ParseRawPrivateKeyWithPassphrase([]byte(text), []byte(passphrase))
	}
	if err != nil {
		return nil, err
	}
	block, err := ssh.MarshalPrivateKey(key, "")
	if err != nil {
		return nil, err
	}
	signer, err := ssh.NewSignerFromKey(key)
	if err != nil {
		return nil, err
	}
	return map[string]any{"key": string(pem.EncodeToMemory(block)), "fingerprint": ssh.FingerprintSHA256(signer.PublicKey()), "kind": signer.PublicKey().Type()}, nil
}

//export rexnet_command
func rexnet_command(raw *C.char) *C.char {
	var request struct {
		Type       string          `json:"type"`
		Config     configuration   `json:"config"`
		Interfaces []interfaceInfo `json:"interfaces"`
		Key        string          `json:"key"`
		Passphrase string          `json:"passphrase"`
	}
	var result any = map[string]any{}
	err := json.Unmarshal([]byte(C.GoString(raw)), &request)
	if err == nil {
		switch request.Type {
		case "configure":
			err = configure(request.Config)
		case "interfaces":
			state.Lock()
			state.interfaces = request.Interfaces
			state.Unlock()
		case "status":
			result = status()
		case "login":
			var server *tsnet.Server
			server, err = tailServer()
			if err == nil {
				local, e := server.LocalClient()
				err = e
				if err == nil {
					ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
					err = local.StartLoginInteractive(ctx)
					cancel()
				}
			}
		case "cancel_login":
			err = cancelLogin()
		case "import_key":
			result, err = importSSHKey(request.Key, request.Passphrase)
		default:
			err = errors.New("unknown network command")
		}
	}
	if err != nil {
		result = map[string]any{"error": err.Error()}
	}
	bytes, _ := json.Marshal(result)
	return C.CString(string(bytes))
}

//export rexnet_dial
func rexnet_dial(raw *C.char, milliseconds C.int, errorOut **C.char) C.int {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(milliseconds)*time.Millisecond)
	defer cancel()
	connection, err := dial(ctx, C.GoString(raw))
	return relay(connection, err, errorOut)
}

//export rexnet_probe_dial
func rexnet_probe_dial(raw *C.char, options *C.char, milliseconds C.int, errorOut **C.char) C.int {
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(milliseconds)*time.Millisecond)
	defer cancel()
	var draft probeOptions
	err := json.Unmarshal([]byte(C.GoString(options)), &draft)
	if err != nil {
		return relay(nil, err, errorOut)
	}
	connection, err := dialProbe(ctx, C.GoString(raw), draft)
	return relay(connection, err, errorOut)
}

func relay(connection net.Conn, err error, errorOut **C.char) C.int {
	if err != nil {
		*errorOut = C.CString(err.Error())
		return -1
	}
	pair, err := unix.Socketpair(unix.AF_UNIX, unix.SOCK_STREAM, 0)
	if err != nil {
		connection.Close()
		*errorOut = C.CString(err.Error())
		return -1
	}
	unix.CloseOnExec(pair[0])
	unix.CloseOnExec(pair[1])
	file := os.NewFile(uintptr(pair[1]), "rexnet-relay")
	local, err := net.FileConn(file)
	file.Close()
	if err != nil {
		unix.Close(pair[0])
		connection.Close()
		*errorOut = C.CString(err.Error())
		return -1
	}
	go func() {
		defer local.Close()
		defer connection.Close()
		done := make(chan struct{})
		go func() { io.Copy(connection, local); connection.Close(); close(done) }()
		io.Copy(local, connection)
		local.Close()
		<-done
	}()
	return C.int(pair[0])
}

//export rexnet_free
func rexnet_free(pointer unsafe.Pointer) { C.free(pointer) }
func main()                              {}
