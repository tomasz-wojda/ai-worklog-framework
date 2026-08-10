import socket
import ssl
from functools import lru_cache
from typing import Optional, Tuple
from urllib.parse import urlparse


def https_context_for(url: str) -> Optional[ssl.SSLContext]:
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.hostname:
        return None
    port = parsed.port or 443
    return ssl_context_for(parsed.hostname, port)


@lru_cache(maxsize=128)
def ssl_context_for(host: str, port: int) -> ssl.SSLContext:
    certs = _extract_peer_certs(host, port)
    context = ssl.create_default_context()
    context.check_hostname = False
    context.verify_mode = ssl.CERT_REQUIRED
    for pem in certs:
        context.load_verify_locations(cadata=pem)
    return context


def _extract_peer_certs(host: str, port: int, timeout: int = 10) -> Tuple[str, ...]:
    probe = ssl.create_default_context()
    probe.check_hostname = False
    probe.verify_mode = ssl.CERT_NONE
    with socket.create_connection((host, port), timeout=timeout) as sock:
        with probe.wrap_socket(sock, server_hostname=host) as ssock:
            chain_getter = getattr(ssock, "getpeercert_chain", None)
            if chain_getter:
                der_certs = chain_getter()
                if der_certs:
                    return tuple(ssl.DER_cert_to_PEM_cert(cert) for cert in der_certs)
            der = ssock.getpeercert(binary_form=True)
            if not der:
                raise ssl.SSLError("No peer certificate")
            return (ssl.DER_cert_to_PEM_cert(der),)
