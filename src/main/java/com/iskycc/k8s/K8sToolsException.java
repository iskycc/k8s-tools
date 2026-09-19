package com.iskycc.k8s;

/**
 * k8s-tools 统一运行时异常。
 */
public class K8sToolsException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public K8sToolsException(String message) {
        super(message);
    }

    public K8sToolsException(String message, Throwable cause) {
        super(message, cause);
    }
}
