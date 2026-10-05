package pj;

// Batch B (B4): a Java interface whose methods return a single-method interface, for kt/reify (ckway.round9-test)
public interface JRet9 {
    Runnable task();
    java.util.function.Supplier<String> supplier();
    java.util.function.IntUnaryOperator op();
    int number();
    String text();
}
