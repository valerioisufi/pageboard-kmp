# Module drawview-ui

The editor of DrawView in an application: `EditorScreen` (the editor of a controller, with its
extensions), `DrawScreen`, the bars, the tool popovers, the dialogs, the commands of the menus and
of the shortcuts (`CommandRoot`, `CommandHost`, `DrawViewMenuBar` on the desktop), the adaptive
layout (`AdaptiveRoot`) and the theme (`DrawViewTheme`).

An application adds its own content to the editor with an `EditorExtension` (top bar actions,
panels, commands, overlays), which receives the `EditorContext` of the document shown. No store:
see `drawview-session` for the documents of a store.
