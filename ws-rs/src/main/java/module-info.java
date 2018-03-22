module net.www_eee.util.serialization.ws {
  requires transitive org.eclipse.jdt.annotation;
  requires transitive java.xml;
  requires transitive java.xml.ws;
  requires transitive java.ws.rs;
  requires transitive net.www_eee.util.serialization;
  requires java.naming;

  exports net.www_eee.util.serialization.ws.rs.provider.xml;
  exports net.www_eee.util.serialization.ws.rs.provider.xml.soap;
}
