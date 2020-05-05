module com.hubick.xml_stream_serialization.ws_rs {
  requires transitive org.eclipse.jdt.annotation;
  requires transitive java.ws.rs;
  requires transitive com.hubick.xml_stream_serialization;
  requires java.xml.soap;
  requires java.naming;

  exports com.hubick.xml_stream_serialization.ws.rs.provider.xml;
  exports com.hubick.xml_stream_serialization.ws.rs.provider.xml.soap;
}
