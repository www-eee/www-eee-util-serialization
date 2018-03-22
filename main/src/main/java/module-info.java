module net.www_eee.util.serialization {
  requires transitive org.eclipse.jdt.annotation;
  requires transitive java.xml;
  requires java.xml.ws;

  exports net.www_eee.util.serialization.introspection;
  exports net.www_eee.util.serialization.xml;
}
