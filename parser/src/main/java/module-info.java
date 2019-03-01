module net.www_eee.util.serialization.parser {
  requires transitive org.eclipse.jdt.annotation;
  requires transitive java.xml;
  requires transitive java.desktop;
  requires java.xml.soap;
  requires java.xml.ws;
  requires org.jooq;
  requires java.sql;

  exports net.www_eee.util.serialization.parser.xml;
  exports net.www_eee.util.serialization.parser.xml.soap;
}
