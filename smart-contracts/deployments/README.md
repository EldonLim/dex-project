# Local deployment metadata

`npm run deploy:local` writes two generated files here:

- `localhost.json` contains safe chain identity, contract addresses,
  transaction hashes, and deterministic acceptance-fixture values.
- `localhost.env` contains the matching backend environment, including a
  randomly generated executor key funded only on the disposable local node.

Both files are ignored because they become invalid whenever the local chain is
reset. Never copy the generated executor key into a committed file or reuse it
outside local development.

The deployment ID incorporates the deployment-block hash. The backend also
validates that complete block hash before indexing, so projections from an old
local chain cannot be silently reused after a reset.
