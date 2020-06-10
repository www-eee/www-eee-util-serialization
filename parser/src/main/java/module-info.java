module com.hubick.xml_stream_serialization.parser {
  requires transitive org.eclipse.jdt.annotation;
  requires transitive java.xml;
  requires transitive java.desktop;
  requires java.xml.soap;
  requires java.xml.ws;
  requires org.jooq;
  requires com.hubick.util.jooq;
  requires java.sql;

  exports com.hubick.xml_stream_serialization.parser.xml;
  exports com.hubick.xml_stream_serialization.parser.xml.soap;
}
