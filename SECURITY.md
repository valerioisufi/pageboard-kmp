# Security

## Reporting a vulnerability

Report it privately, through
[the private reporting of GitHub](https://github.com/valerioisufi/DrawView-KMP/security/advisories/new),
or by email to pencilnotes@proton.me. Please do not open a public issue.

Say what is affected (a module, a version or a commit), how to reproduce it and what an attacker
gains. You will have an answer within a week, and the fix is released before the details are made
public.

## What matters most

* **The sync.** Another device is untrusted input, whether it comes over the internet or on the
  same Wi-Fi, and so is a member of a shared document. A frame, a message or an operation that makes
  an application crash, allocate without bound, accept what its sender may not send, or read what it
  should not, is a vulnerability (`drawview-sync-protocol`, `-sync`, `-sync-lan`, `-sync-iroh`).
* **The files opened.** A PDF, an image, an archive (`.drawview`) or a note pasted from elsewhere.
* **The notes of the users.** Anything that loses or corrupts them.
* **Secrets.** The keys of the devices and the API keys kept by the applications.

## Supported versions

The latest release of the libraries and of PencilNotes. The libraries are 0.x: a fix comes in a new
version, not in an older line.
