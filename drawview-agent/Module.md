# Module drawview-agent

What an external model can read of a DrawView document and propose for it: the tools
(`DocumentTools`), the proposals waiting for the user (`ProposalBook`), the changes they become when
accepted (`ProposalApplier`), who may reach which document (`AgentAccess`) and the messages of a
client, as JSON-RPC and the Model Context Protocol (`AgentEndpoint`). `ExternalAgents` gathers them
for an application.

Headless: no Compose and no socket. The way in (a local socket, a command line) belongs to the
application, the review to `drawview-agent-ui`. See `docs/agent-tools.md`.
