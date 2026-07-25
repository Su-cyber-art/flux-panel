package socket

import "testing"

func TestCommandChangesConfig(t *testing.T) {
	mutatingCommands := []string{
		"AddService",
		"UpdateService",
		"DeleteService",
		"PauseService",
		"ResumeService",
		"AddChains",
		"UpdateChains",
		"DeleteChains",
		"AddLimiters",
		"UpdateLimiters",
		"DeleteLimiters",
		"SetProtocol",
	}

	for _, command := range mutatingCommands {
		t.Run(command, func(t *testing.T) {
			if !commandChangesConfig(command) {
				t.Fatalf("%s must persist the updated configuration", command)
			}
		})
	}
}

func TestReadOnlyCommandsDoNotChangeConfig(t *testing.T) {
	for _, command := range []string{"TcpPing", "call", "UnknownCommand"} {
		t.Run(command, func(t *testing.T) {
			if commandChangesConfig(command) {
				t.Fatalf("%s must not persist the runtime configuration", command)
			}
		})
	}
}
