# Module pageboard-agent

What an external model can read of a Pageboard document and propose for it: the tools
(`DocumentTools`), the proposals waiting for the user (`ProposalBook`), where their text would go
(`ProposalPlanner`), the changes they become when accepted (`ProposalApplier`), who may reach which document (`AgentAccess`) and the messages of a
client, as JSON-RPC and the Model Context Protocol (`AgentEndpoint`). `ExternalAgents` gathers them
for an application.

Headless: no Compose and no socket. The way in (a local socket, a command line) belongs to the
application, the review to `pageboard-agent-ui`. See `docs/plans/agent-tools.md` and `docs/plans/agent-placement.md`.
