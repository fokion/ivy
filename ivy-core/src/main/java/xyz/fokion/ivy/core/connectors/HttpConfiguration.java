package xyz.fokion.ivy.core.connectors;

import java.util.List;
import java.util.Map;

import xyz.fokion.ivy.spi.Configuration;
import xyz.fokion.ivy.spi.ConfigurationProperty;

public final class HttpConfiguration implements Configuration {

    private String method = "";
    private String url = "";
    private String path = "";
    private Map<String, String> queryParameters = Map.of();
    private String body = "";
    private String bodyFile = "";
    private boolean preserveBodyFile;
    private Object multipartForm;
    private Map<String, String> headers = Map.of();
    private boolean ignoreVerifySsl;
    private String basicAuthUser = "";
    private String basicAuthPassword = "";
    private boolean skipHeaders;
    private boolean skipBody;
    private String proxy = "";
    private List<String> resolve = List.of();
    private boolean noFollowRedirect;
    private String unixSock = "";
    private String tlsClientCert = "";
    private String tlsClientKey = "";
    private String tlsRootCa = "";

    @ConfigurationProperty
    public void setMethod(String method) {
        this.method = method;
    }

    @ConfigurationProperty
    public void setUrl(String url) {
        this.url = url;
    }

    @ConfigurationProperty
    public void setPath(String path) {
        this.path = path;
    }

    @ConfigurationProperty(name = "query_parameters")
    public void setQueryParameters(Map<String, String> queryParameters) {
        this.queryParameters = queryParameters;
    }

    @ConfigurationProperty
    public void setBody(String body) {
        this.body = body;
    }

    @ConfigurationProperty(name = "bodyfile")
    public void setBodyFile(String bodyFile) {
        this.bodyFile = bodyFile;
    }

    @ConfigurationProperty(name = "preserve_bodyfile")
    public void setPreserveBodyFile(boolean preserveBodyFile) {
        this.preserveBodyFile = preserveBodyFile;
    }

    @ConfigurationProperty(name = "multipart_form", help = "fields; values starting with @ are files")
    public void setMultipartForm(Object multipartForm) {
        this.multipartForm = multipartForm;
    }

    @ConfigurationProperty
    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    @ConfigurationProperty(name = "ignore_verify_ssl")
    public void setIgnoreVerifySsl(boolean ignoreVerifySsl) {
        this.ignoreVerifySsl = ignoreVerifySsl;
    }

    @ConfigurationProperty(name = "basic_auth_user")
    public void setBasicAuthUser(String basicAuthUser) {
        this.basicAuthUser = basicAuthUser;
    }

    @ConfigurationProperty(name = "basic_auth_password", secret = true)
    public void setBasicAuthPassword(String basicAuthPassword) {
        this.basicAuthPassword = basicAuthPassword;
    }

    @ConfigurationProperty(name = "skip_headers")
    public void setSkipHeaders(boolean skipHeaders) {
        this.skipHeaders = skipHeaders;
    }

    @ConfigurationProperty(name = "skip_body")
    public void setSkipBody(boolean skipBody) {
        this.skipBody = skipBody;
    }

    @ConfigurationProperty
    public void setProxy(String proxy) {
        this.proxy = proxy;
    }

    @ConfigurationProperty(help = "host:port:address entries overriding name resolution")
    public void setResolve(List<String> resolve) {
        this.resolve = resolve;
    }

    @ConfigurationProperty(name = "no_follow_redirect")
    public void setNoFollowRedirect(boolean noFollowRedirect) {
        this.noFollowRedirect = noFollowRedirect;
    }

    @ConfigurationProperty(name = "unix_sock")
    public void setUnixSock(String unixSock) {
        this.unixSock = unixSock;
    }

    @ConfigurationProperty(name = "tls_client_cert")
    public void setTlsClientCert(String tlsClientCert) {
        this.tlsClientCert = tlsClientCert;
    }

    @ConfigurationProperty(name = "tls_client_key", secret = true)
    public void setTlsClientKey(String tlsClientKey) {
        this.tlsClientKey = tlsClientKey;
    }

    @ConfigurationProperty(name = "tls_root_ca")
    public void setTlsRootCa(String tlsRootCa) {
        this.tlsRootCa = tlsRootCa;
    }

    public String method() {
        return method;
    }

    public String url() {
        return url;
    }

    public String path() {
        return path;
    }

    public Map<String, String> queryParameters() {
        return queryParameters;
    }

    public String body() {
        return body;
    }

    public String bodyFile() {
        return bodyFile;
    }

    public boolean preserveBodyFile() {
        return preserveBodyFile;
    }

    public Object multipartForm() {
        return multipartForm;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public boolean ignoreVerifySsl() {
        return ignoreVerifySsl;
    }

    public String basicAuthUser() {
        return basicAuthUser;
    }

    public String basicAuthPassword() {
        return basicAuthPassword;
    }

    public boolean skipHeaders() {
        return skipHeaders;
    }

    public boolean skipBody() {
        return skipBody;
    }

    public String proxy() {
        return proxy;
    }

    public List<String> resolve() {
        return resolve;
    }

    public boolean noFollowRedirect() {
        return noFollowRedirect;
    }

    public String unixSock() {
        return unixSock;
    }

    public String tlsClientCert() {
        return tlsClientCert;
    }

    public String tlsClientKey() {
        return tlsClientKey;
    }

    public String tlsRootCa() {
        return tlsRootCa;
    }
}
