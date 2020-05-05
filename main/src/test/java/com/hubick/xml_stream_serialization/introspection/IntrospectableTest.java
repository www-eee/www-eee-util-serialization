/*
 * Copyright 2017-2020 by Chris Hubick. All Rights Reserved.
 * 
 * This work is licensed under the terms of the "GNU AFFERO GENERAL PUBLIC LICENSE" version 3, as published by the Free
 * Software Foundation <http://www.gnu.org/licenses/>, plus additional permissions, a copy of which you should have
 * received in the file LICENSE.txt.
 */

package com.hubick.xml_stream_serialization.introspection;

import java.net.*;
import java.util.*;

import org.eclipse.jdt.annotation.*;

import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;


@SuppressWarnings("javadoc")
@NonNullByDefault
public class IntrospectableTest {
  protected static final URI TEST_NS_URI = URI.create("https://chris.hubick.com/ns/"); // Something random and short.

  @Test
  public void testIntrospection() throws Exception {
    final StreetAddress sa1 = new StreetAddress("John St", 42, "R6H 2C4");
    final Apartment apt1 = new Apartment(sa1, "ING", 35);
    apt1.addTenant(1, "Joe Smithers");
    apt1.addTenant(5, "John Doehead");
    apt1.addTenant(7, "Joanne Deer");
    final StreetAddress sa2 = new StreetAddress("Doe St", 1024, "R6H 2C5");
    final House hs1 = new House(sa2, "TD", 5);
    final StreetAddress sa3 = new StreetAddress("Doe St", 1025, "R6H 2C5");
    final House hs2 = new House(sa3, "TD", 6);
    final Agent ag1 = new Agent("Maury", hs1, hs2);
    ag1.addListing(apt1);
    final Broker br1 = new Broker();
    br1.addAgent(55, ag1);
    br1.addPropertyType("Commercial");
    br1.addPropertyType("Residential");
    assertEquals("<Broker xmlns=\"https://chris.hubick.com/ns/\"><Agent EmployeeNumber=\"55\"><Agent Name=\"Maury\"><PrimaryHouse><House InsuranceProvider=\"TD\" YardSize=\"5\"><StreetAddress Street=\"Doe St\" Number=\"1024\" PostalCode=\"R6H 2C5\"/></House></PrimaryHouse><SecondaryHouse><House InsuranceProvider=\"TD\" YardSize=\"6\"><StreetAddress Street=\"Doe St\" Number=\"1025\" PostalCode=\"R6H 2C5\"/></House></SecondaryHouse><Listings><Apartment InsuranceProvider=\"ING\" Suites=\"35\"><StreetAddress Street=\"John St\" Number=\"42\" PostalCode=\"R6H 2C4\"/><Tenant Suite=\"1\">Joe Smithers</Tenant><Tenant Suite=\"5\">John Doehead</Tenant><Tenant Suite=\"7\">Joanne Deer</Tenant></Apartment></Listings></Agent></Agent><PropertyType>Commercial</PropertyType><PropertyType>Residential</PropertyType></Broker>", br1.introspect().toXMLString());
    return;
  }

  public static final class StreetAddress implements Introspectable {
    protected final String street;
    protected final int number;
    protected final String postalCode;

    public StreetAddress(final String street, final int number, final String postalCode) {
      this.street = street;
      this.number = number;
      this.postalCode = postalCode;
      return;
    }

    public String getStreet() {
      return street;
    }

    public int getNumber() {
      return number;
    }

    public String getPostalCode() {
      return postalCode;
    }

    @Override
    public Info<? extends StreetAddress> introspect() {
      return new Info.Builder<StreetAddress>(StreetAddress.class, TEST_NS_URI, false).attr("Street", street).attr("Number", number).attr("PostalCode", postalCode).build();
    }

  } // StreetAddress

  public static abstract class Building implements Introspectable {
    protected final StreetAddress streetAddress;
    protected final @Nullable String insuranceProvider;

    public Building(final StreetAddress streetAddress, final @Nullable String insuranceProvider) {
      this.streetAddress = streetAddress;
      this.insuranceProvider = insuranceProvider;
      return;
    }

    public StreetAddress getStreetAddress() {
      return streetAddress;
    }

    public Optional<String> getInsuranceProvider() {
      return Optional.ofNullable(insuranceProvider);
    }

    @Override
    public Info<? extends Building> introspect() {
      return new Info.Builder<Building>(Building.class, TEST_NS_URI, false).complexChild(StreetAddress.class, false, "StreetAddress", streetAddress).attr("InsuranceProvider", insuranceProvider).build();
    }

  } // Building

  public static class Apartment extends Building {
    protected final int suites;
    protected final Map<Integer,String> tenants = new HashMap<Integer,String>();

    public Apartment(final StreetAddress streetAddress, final @Nullable String insuranceProvider, final int suites) {
      super(streetAddress, insuranceProvider);
      if (suites <= 0) throw new IllegalArgumentException("Invalid suites number");
      this.suites = suites;
      return;
    }

    public int getSuites() {
      return suites;
    }

    public void addTenant(final int suite, final String name) {
      if ((suite < 1) || (suite > suites)) throw new IllegalArgumentException("Invalid suite number");
      tenants.put(suite, name);
      return;
    }

    public Map<Integer,String> getTenants() {
      return tenants;
    }

    @Override
    public final Info<? extends Apartment> introspect() {
      return super.introspect().superCast(Building.class).build().type(Apartment.class).attr("Suites", suites).primitiveChild(Integer.class, false, "Suite", String.class, false, "Tenants", "Tenant", tenants).build();
    }

  } // Apartment

  public static class House extends Building {
    protected final Integer yardSize;

    public House(final StreetAddress streetAddress, final @Nullable String insuranceProvider, final Integer yardSize) {
      super(streetAddress, insuranceProvider);
      this.yardSize = yardSize;
      return;
    }

    public Integer getYardSize() {
      return yardSize;
    }

    @Override
    public final Info<? extends House> introspect() {
      return super.introspect().superCast(Building.class).build().type(House.class).attr("YardSize", yardSize).build();
    }

  } // House

  public static class Agent implements Introspectable {
    protected final String name;
    protected final House primaryHouse;
    protected final @Nullable House secondaryHouse;
    protected final List<Building> listings = new ArrayList<>();

    public Agent(final String name, final House primaryHouse, final @Nullable House secondaryHouse) {
      this.name = name;
      this.primaryHouse = primaryHouse;
      this.secondaryHouse = secondaryHouse;
      return;
    }

    public String getName() {
      return name;
    }

    public House getPrimaryHouse() {
      return primaryHouse;
    }

    public @Nullable House getSecondaryHouse() {
      return secondaryHouse;
    }

    public void addListing(final Building building) {
      listings.add(building);
      return;
    }

    public List<Building> getListings() {
      return listings;
    }

    @Override
    public Info<? extends Agent> introspect() {
      return new Info.Builder<Agent>(Agent.class, TEST_NS_URI, false).attr("Name", name).complexChild(House.class, false, "PrimaryHouse", primaryHouse).complexChild(House.class, false, "SecondaryHouse", secondaryHouse).complexChild(Building.class, true, "Listings", "Listing", listings).build();
    }

  } // Agent

  public static class Broker implements Introspectable {
    protected final Map<Integer,Agent> agents = new HashMap<>();
    protected final Set<String> propertyTypes = new LinkedHashSet<>();

    public Broker() {
      return;
    }

    public void addAgent(final int employeeNumber, final Agent agent) {
      agents.put(employeeNumber, agent);
      return;
    }

    public Map<Integer,Agent> getAgents() {
      return agents;
    }

    public void addPropertyType(final String type) {
      propertyTypes.add(type);
      return;
    }

    public Set<String> getPropertyTypes() {
      return propertyTypes;
    }

    @Override
    public Info<? extends Broker> introspect() {
      return new Info.Builder<Broker>(Broker.class, TEST_NS_URI, false).complexChild(Integer.class, false, "EmployeeNumber", Agent.class, false, "Agents", "Agent", agents).primitiveChild(String.class, false, "PropertyTypes", "PropertyType", propertyTypes).build();
    }

  } // Broker

}
