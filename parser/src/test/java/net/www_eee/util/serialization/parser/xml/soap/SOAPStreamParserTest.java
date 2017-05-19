/*
 * Copyright 2016-2017 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, plus additional permissions, a copy of which you should have
 * received in the file LICENSE.txt.
 */

package net.www_eee.util.serialization.parser.xml.soap;

import java.beans.*;
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
  /**
   * The {@link ExpectedException} for the {@link Test}'s.
   */
  @Rule
  public ExpectedException thrown = ExpectedException.none();
  protected static final SOAPStreamParser<Departure> DEPARTURE_STREAM_PARSER;
  static {
    final SOAPStreamParser.SchemaBuilder<? extends SOAPStreamParser.SchemaBuilder<?>> schema = SOAPStreamParser.buildSchema(URI.create("http://www_eee.net/ns/"));
    schema.defineSimpleElement("departureYear", Year.class, (ctx, value) -> Year.parse(value), true).defineHeaderElementWithChildBuilder().addChild("departureYear").completeDefinition().defineStringElement("departing").defineSimpleElement("departureMonthDay", MonthDay.class, MonthDay::parse);
    schema.defineElementWithInjectedTargetBuilder("departure", Departure.class).injectChildObject("Departing", "departing").injectChildObject("DepartureMonthDay", "departureMonthDay").injectSavedObject("DepartureYear", "departureYear").completeDefinition();
    // schema.defineElementWithChildBuilder("departure", Departure.class, (ctx) -> new Departure(ctx.getRequiredChildValue("departing", String.class), ctx.getRequiredChildValue("departureMonthDay", MonthDay.class).atYear(ctx.getRequiredSavedValue("departureYear", Year.class).getValue())), false).addChild("departing").addChild("departureMonthDay").completeDefinition();
    schema.defineContainerElementWithChildBuilder("departures").addChild("departure").addChild(SOAPStreamParser.FAULT_QNAME).completeDefinition().defineBodyElement("departures");
    DEPARTURE_STREAM_PARSER = schema.defineEnvelopeElement(true).createSOAPParser(Departure.class, "departures", "departure");
  }

  /**
   * Test parsing the data while ignoring extra elements.
   * 
   * @throws Exception If there was a problem executing this test.
   */
  @Test
  public void testIgnoreExtra() throws Exception {
    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_ignore_extra.xml");
    final Stream<Departure> departures = StreamSupport.stream(Spliterators.spliteratorUnknownSize(DEPARTURE_STREAM_PARSER.parse(testURL.openStream()), Spliterator.ORDERED | Spliterator.NONNULL), false);
    assertEquals("Canada[2001-01-01], USA[2001-02-01], Australia[2001-03-01]", departures.map(Object::toString).collect(Collectors.joining(", ")));
    return;
  }

  /**
   * Test global (top-level) fault handling.
   * 
   * @throws Exception If there was a problem executing this test.
   */
  @Test
  public void testGlobalFaultThrown() throws Exception {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Server went boom.");

    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_global_fault.xml");
    try {
      DEPARTURE_STREAM_PARSER.parse(testURL.openStream());
    } catch (SOAPStreamParser.ElementValueParsingException evpe) {
      throw evpe.getCause();
    }
    return;
  }

  /**
   * Test local fault handling.
   * 
   * @throws Exception If there was a problem executing this test.
   */
  @Test
  public void testLocalFaultThrown() throws Exception {
    thrown.expect(SOAPFaultException.class);
    thrown.expectMessage("Invalid departure record.");

    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_local_fault.xml");
    try {
      final Iterator<Departure> departures = DEPARTURE_STREAM_PARSER.parse(testURL.openStream());
      assertEquals("Canada[2001-01-01]", departures.next().toString());
      assertEquals("USA[2001-02-01]", departures.next().toString());
      departures.next().toString();
    } catch (SOAPStreamParser.ElementValueParsingException evpe) {
      throw evpe.getCause();
    }
    return;
  }

  /**
   * Test local fault recovery (can you continue iterating afterwards).
   * 
   * @throws Exception If there was a problem executing this test.
   */
  @Test
  public void testLocalFaultRecovery() throws Exception {
    final URL testURL = SOAPStreamParserTest.class.getResource("/net/www_eee/util/serialization/parser/xml/soap/departures_local_fault.xml");
    final Iterator<Departure> departures = DEPARTURE_STREAM_PARSER.parse(testURL.openStream());
    assertEquals("Canada[2001-01-01]", departures.next().toString());
    assertEquals("USA[2001-02-01]", departures.next().toString());
    try {
      departures.next().toString();
      fail("Expected SOAPFaultException");
    } catch (SOAPStreamParser.ElementValueParsingException evpe) {}
    assertEquals("Australia[2001-03-01]", departures.next().toString());

    return;
  }

  /**
   * An example data model class representing a departure.
   */
  public static class Departure {
    private final String departing;
    private final LocalDate date;

    /**
     * Construct a new <code>Departure</code>.
     * 
     * @param departing The location of the departure.
     * @param date The departure date.
     */
    public Departure(final String departing, final LocalDate date) {
      this.departing = departing;
      this.date = date;
      return;
    }

    /**
     * Construct a new <code>Departure</code>.
     * 
     * @param departing The location of the departure.
     * @param departureMonthDay The month and day of the departure date.
     * @param departureYear The year of the departure date.
     */
    @ConstructorProperties({ "Departing", "DepartureMonthDay", "DepartureYear" })
    public Departure(final String departing, final MonthDay departureMonthDay, final @Nullable Year departureYear) {
      this(departing, departureMonthDay.atYear((departureYear != null) ? departureYear.getValue() : LocalDateTime.now().getYear()));
      return;
    }

    @Override
    public String toString() {
      return departing + '[' + date + ']';
    }

  } // Departure

}
