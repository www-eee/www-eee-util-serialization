module net.www_eee.util.serialization.ws {
  requires transitive org.eclipse.jdt.annotation;
  requires transitive java.ws.rs;
  requires transitive net.www_eee.util.serialization;
  requires java.xml.soap;
  requires java.naming;

  exports net.www_eee.util.serialization.ws.rs.provider.xml;
  exports net.www_eee.util.serialization.ws.rs.provider.xml.soap;
}
