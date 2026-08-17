package com.paycore.webhooks;

import com.paycore.common.error.PayCoreException;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Where we are willing to POST. A webhook URL is attacker-controlled input that makes OUR server issue
 * requests, so without this check a merchant could aim us at cloud metadata endpoints or internal services
 * (SSRF). Production allows only public https hosts; local/dev allows anything so tests can use localhost.
 */
public final class WebhookUrlPolicy {

    private final boolean allowPrivate;

    public WebhookUrlPolicy(boolean allowPrivate) {
        this.allowPrivate = allowPrivate;
    }

    public void validate(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw PayCoreException.invalid("url_invalid", "Webhook URL is not a valid URI", "url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if (!(scheme.equals("https") || (allowPrivate && scheme.equals("http")))) {
            throw PayCoreException.invalid("url_scheme", "Webhook URL must use https", "url");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            throw PayCoreException.invalid("url_invalid", "Webhook URL must have a host and no credentials", "url");
        }
        if (allowPrivate) {
            return;
        }
        try {
            for (InetAddress addr : InetAddress.getAllByName(uri.getHost())) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                    throw PayCoreException.invalid("url_private", "Webhook URL must resolve to a public address", "url");
                }
            }
        } catch (UnknownHostException e) {
            throw PayCoreException.invalid("url_unresolvable", "Webhook host could not be resolved", "url");
        }
    }
}
