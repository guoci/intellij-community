class MyTest {

    private void paramTypeMismatch() {
        String.join(",", <error descr="'join(java.lang.CharSequence, java.lang.CharSequence...)' in 'java.lang.String' cannot be applied to '(java.lang.String, java.lang.String, int, java.lang.String)'">"start"</error>, <error descr="'join(java.lang.CharSequence, java.lang.CharSequence...)' in 'java.lang.String' cannot be applied to '(java.lang.String, java.lang.String, int, java.lang.String)'">1</error>, <error descr="'join(java.lang.CharSequence, java.lang.CharSequence...)' in 'java.lang.String' cannot be applied to '(java.lang.String, java.lang.String, int, java.lang.String)'">"end"</error>);
    }
}