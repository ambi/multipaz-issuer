# multipaz-issuer

## Run

`.env`:

```env
ENTRA_TENANT_ID=***
ENTRA_CLIENT_ID=***
ENTRA_CLIENT_SECRET=***
BASE_URL=https://{FQDN}
ENTRA_REDIRECT_URI=https://{FQDN}/auth/callback
```

```shell
./gradlew run

ngrok http 8080 # for global HTTPS URI
```

## TODO

- Authorization Endpoint
