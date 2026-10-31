# Command interface layout patches

`inspect_command_interface` now lists `availableCommands` for metadata in a subsystem. Use these exact FQNs when changing its existing command interface through `mutate_command_interface`.

Three operations are added:

```json
[
  {"op":"set_visibility","command":"Catalog.Books.StandardCommand.List","visible":true},
  {"op":"place_commands","group":"CommandGroup.Knowledge","commands":["Catalog.Books.StandardCommand.List"]},
  {"op":"order_commands","group":"CommandGroup.Knowledge","commands":["Catalog.Books.StandardCommand.List"]}
]
```

Create custom command groups through `create_metadata`, inspect the owner, then validate the exact mutation payload with `edt_validate_request`. The token binds the command, group, visibility and ordered list of commands. Groups and commands must already resolve in the EDT model; the tool refuses unknown commands and does not construct unresolved command proxies. A missing interface is created and attached with the native external-property FQN before export. A batch resolves every command/group reference before changing its model. Every requested visibility, placement and order is independently verified after commit; an empty proxy cannot count as a successful write.

Visibility changes the common flag and retains role overrides. Placement removes only the requested commands from other placement groups and their old order entries. Order puts requested commands first, retaining the unspecified tail. Unrelated entries, including platform commands, are preserved. Cleanup (`prune_orphans`/`delete`) must use a separate batch. No role grants or command group ordering changes are implicit.

After installation, verify the resulting model with `inspect_command_interface`, load the extension through EDT, and inspect the actual 1C menu. Unit tests cannot prove menu appearance or permission behavior.
