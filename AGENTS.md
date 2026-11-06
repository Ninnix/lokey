# Agent notes

* Application code must use Kotlin or C11. Do not introduce C++ sources. Python can be used for tools.
* Call the existing inference runtimes through their C APIs; their internals live in separate repositories.
* Use a minimal programming style, like Salvatore Sanfilippo, aka Antirez.
* Add dependencies only when strictly necessary; prefer the standard and existing libraries.
* Comment to explain intent, design, or non-obvious behavior. Skip trivial comments.
* Never announce the coding style or explain the coding philosophy.
