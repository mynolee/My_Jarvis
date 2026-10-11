"""Expose transport-verified client certificate to ASGI without trusting headers."""
import ssl
from uvicorn.protocols.http.h11_impl import H11Protocol


class MutualTLSProtocol(H11Protocol):
    def connection_made(self, transport):
        super().connection_made(transport)
        context = transport.get_extra_info('ssl_object')
        certificate = context.getpeercert(binary_form=True) if context is not None else None
        app = self.app

        async def verified_scope(scope, receive, send):
            scope = dict(scope)
            # Replace, never merge, any unverified peer extension.
            scope['extensions'] = dict(scope.get('extensions', {}))
            scope['extensions']['tls'] = {'client_cert_chain': [ssl.DER_cert_to_PEM_cert(certificate)] if certificate else [],
                                          'client_cert_error': None if certificate else 'missing'}
            await app(scope, receive, send)

        self.app = verified_scope
