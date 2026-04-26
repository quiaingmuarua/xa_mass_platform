# Java Polling Worker Sample

This directory is reserved for the third-party Java polling worker sample.

Planned contract:

- register through `/worker-api/workers/register`
- optional worker-context registration
- online / heartbeat / poll / submit result / offline through `/worker-api/*`
- no reuse of embedded SDK runtime composition or dev-app mock client code
