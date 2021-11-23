package clientCore.exceptions;

public class ClientException extends Exception{
    ClientException() {}

    public ClientException(String message) {
        super(message);
    }

    ClientException(Throwable message) {
        super(message);
    }
}
