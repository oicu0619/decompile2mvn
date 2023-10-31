package oicu;

public class AssertUtils {
    public static void assertion(boolean condition, String conditionFailureMessage)
    {
        if(!condition)
            throw new AssertionError(conditionFailureMessage);
    }
}
