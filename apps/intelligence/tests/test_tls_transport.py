"""Transport adaptation only; not a live TLS handshake or firewall test."""
import ssl
import unittest
from types import SimpleNamespace
from unittest.mock import patch
from uvicorn.protocols.http.h11_impl import H11Protocol
from jarvis_intelligence.tls import MutualTLSProtocol


class TransportTests(unittest.IsolatedAsyncioTestCase):
    async def test_certificate_comes_from_ssl_transport_not_incoming_scope(self):
        seen={}
        async def app(scope, receive, send): seen.update(scope)
        protocol=object.__new__(MutualTLSProtocol);protocol.app=app
        ssl_object=SimpleNamespace(getpeercert=lambda binary_form: b'synthetic-der')
        transport=SimpleNamespace(get_extra_info=lambda key: ssl_object if key=='ssl_object' else None)
        with patch.object(H11Protocol,'connection_made',return_value=None):
            protocol.connection_made(transport)
        original={'extensions':{'tls':{'client_cert_chain':['spoofed']}},'headers':[(b'x-client-cert',b'spoofed')]}
        await protocol.app(original,None,None)
        self.assertEqual(seen['extensions']['tls']['client_cert_chain'],[ssl.DER_cert_to_PEM_cert(b'synthetic-der')])
        self.assertEqual(original['extensions']['tls']['client_cert_chain'],['spoofed'])
    async def test_missing_transport_certificate_fails_closed(self):
        seen={}
        async def app(scope, receive, send): seen.update(scope)
        protocol=object.__new__(MutualTLSProtocol);protocol.app=app
        with patch.object(H11Protocol,'connection_made',return_value=None):
            protocol.connection_made(SimpleNamespace(get_extra_info=lambda key:None))
        await protocol.app({'extensions':{'tls':{'client_cert_chain':['spoofed']}}},None,None)
        self.assertEqual(seen['extensions']['tls']['client_cert_chain'],[])
        self.assertEqual(seen['extensions']['tls']['client_cert_error'],'missing')
