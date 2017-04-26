/*
 * Copyright 2016-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU LESSER GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, a copy of which you should have received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.parser.xml.soap;

import java.net.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

import javax.xml.ws.soap.*;

import org.eclipse.jdt.annotation.*;

import org.junit.*;
import org.junit.rules.*;

import static org.junit.Assert.*;


/**
 * JUnit tests for {@link SOAPStreamParser}.
 */
@NonNullByDefault
public class SOAPStreamParserTest {
  @Rule
  public ExpectedException thrown = ExpectedException.none();
  public static final SOAPStreamParser<Departure> DEPARTURE_STREAM_PARSER;
  static {
    final SOAPStreamParser.SchemaBuilder<Departure,? extends SOAPStreamParser.SchemaBuilder<Departure,?>> builder = SOAPStreamParser.buildSchema(Departure.class, URI.create("http://www_eee.net/ns/"));
    builder.text("departureYear", Year.class, Year::parse, true).header("departureYear").string("departing").text("departureMonthDay", MonthDay.class, MonthDay::parse);
    builder.element("departure", Departure.class, (context) -> new Departure(context.getFirstChildValue("departing", String.class), context.getFirstChildValue("departureMonthDay", MonthDay.class).atYear(context.getSavedValue("departureYear", Year.class).getValue())), false, "departing", "departureMonthDay");
    builder.container("departures", builder.qn("departure"), SOAPStreamParser.FAULT_QNAME).body("departures");
    DEPARTURE_STREAM_PARSER = builder.envelope(true).build("departure");
  }

  /**
   * Test parsing the data while ignoring extra elements.
   */
  @Test
  public void testIgnoreExtra() throws Exception {
    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_ignore_extra.xml");
    System.err.println(DEPARTURE_STREAM_PARSER.parse(testURL.openStream()).collect(Collectors.toList()));
    final Stream<Departure> departures = DEPARTURE_STREAM_PARSER.parse(testURL.openStream());
    assertEquals("Canada[2001-01-01],USA[2001-02-01],Australia[2001-03-01]", departures.map(Object::toString).collect(Collectors.joining(",")));
    return;
  }

  /**
   * Test global (top-level) fault handling.
   */
  @Test
  public void testGlobalFaultThrown() throws Exception {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Server went boom.");

    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_global_fault.xml");
    DEPARTURE_STREAM_PARSER.parse(testURL.openStream());
    return;
  }

  /**
   * Test local fault handling.
   */
  @Test
  public void testLocalFaultThrown() throws Exception {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Invalid departure record.");

    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_local_fault.xml");
    final Iterator<Departure> departures = DEPARTURE_STREAM_PARSER.parse(testURL.openStream()).iterator();
    assertEquals("Canada[2001-01-01]", departures.next().toString());
    assertEquals("USA[2001-02-01]", departures.next().toString());
    departures.next().toString();
    return;
  }

  /**
   * Test local fault recovery (can you continue iterating afterwards).
   */
  @Test
  public void testLocalFaultRecovery() throws Exception {
    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_local_fault.xml");
    final Iterator<Departure> departures = DEPARTURE_STREAM_PARSER.parse(testURL.openStream()).iterator();
    assertEquals("Canada[2001-01-01]", departures.next().toString());
    assertEquals("USA[2001-02-01]", departures.next().toString());
    try {
      departures.next().toString();
      fail("Expected SOAPFaultException");
    } catch (SOAPFaultException sfe) {}
    assertEquals("Australia[2001-03-01]", departures.next().toString());

    return;
  }

  public static class Departure {
    private final String departing;
    private final LocalDate date;

    public Departure(final String departing, final LocalDate date) {
      this.departing = departing;
      this.date = date;
      return;
    }

    @Override
    public String toString() {
      return departing + '[' + date + ']';
    }

  } // Departure

}
