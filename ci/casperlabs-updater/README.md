# casperlabs-updater

A tool to update versions of all published CasperLabs packages.

# Usage

The tool iterates through each published CasperLabs package, asking for a new version for each or automatically bumping the major, minor or patch version if `--bump=[major|minor|patch]` was specified.  Once a valid version is specified, all files dependent on that version are updated.

If you run the tool from its own directory it will expect to find the execution-engine directory at ../../execution-engine.  Alternatively, you can give the path to the execution-engine directory via `--root-dir`.    

To see a list of files which will be affected, or to check that the tool's regex matches are up to date, run the tool with `--dry-run`.

## License

`casperlabs-updater` is licensed under [CasperLabs Open Source License, Version 1.0](../../LICENSE).
