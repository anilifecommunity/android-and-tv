# Legacy receiver

This Hugging Face Space receiver is retained as an undeployed fallback. The supported diagnostics
ingress is the Cloudflare Worker in `infrastructure/diagnostics-gateway`; follow that directory's
`DEPLOYMENT.md` for deployment and rollback instructions.

Do not configure an Android build to use this Space and never embed Hugging Face credentials in
the application.
