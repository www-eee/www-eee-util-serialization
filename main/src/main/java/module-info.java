module com.hubick.xml_stream_serialization {
  requires transitive org.eclipse.jdt.annotation;
  requires transitive java.xml;
  requires java.xml.soap;

  exports com.hubick.xml_stream_serialization.introspection;
  exports com.hubick.xml_stream_serialization.xml;
}
