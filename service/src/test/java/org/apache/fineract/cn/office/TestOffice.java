/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.cn.office;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.fineract.cn.anubis.test.v1.TenantApplicationSecurityEnvironmentTestRule;
import org.apache.fineract.cn.api.context.AutoUserContext;
import org.apache.fineract.cn.office.api.v1.EventConstants;
import org.apache.fineract.cn.office.api.v1.client.*;
import org.apache.fineract.cn.office.api.v1.domain.*;
import org.apache.fineract.cn.office.rest.config.OfficeRestConfiguration;
import org.apache.fineract.cn.office.util.AddressFactory;
import org.apache.fineract.cn.office.util.EmployeeFactory;
import org.apache.fineract.cn.office.util.OfficeFactory;
import org.apache.fineract.cn.test.env.TestEnvironment;
import org.apache.fineract.cn.test.fixture.TenantDataStoreContextTestRule;
import org.apache.fineract.cn.test.fixture.cassandra.CassandraInitializer;
import org.apache.fineract.cn.test.fixture.mariadb.MariaDBInitializer;
import org.apache.fineract.cn.test.listener.EnableEventRecording;
import org.apache.fineract.cn.test.listener.EventRecorder;
import org.junit.*;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.netflix.feign.EnableFeignClients;
import org.springframework.cloud.netflix.ribbon.RibbonClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.restdocs.JUnitRestDocumentation;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document;
import static org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.delete;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.get;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.post;
import static org.springframework.restdocs.mockmvc.RestDocumentationRequestBuilders.put;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessRequest;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.preprocessResponse;
import static org.springframework.restdocs.operation.preprocess.Preprocessors.prettyPrint;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
public class TestOffice {

  @Rule
  public final JUnitRestDocumentation restDocumentation = new JUnitRestDocumentation("src/doc/generated-snippets/test-office");

  @Autowired
  private WebApplicationContext context;

  private MockMvc mockMvc;

  final String path = "/office/v1";

  @Before
  public void setUp(){

    this.mockMvc = MockMvcBuilders.webAppContextSetup(this.context)
            .apply(documentationConfiguration(this.restDocumentation))
            .alwaysDo(document("{method-name}", preprocessRequest(prettyPrint()), preprocessResponse(prettyPrint())))
            .build();
  }

  private static final String APP_NAME = "office-v1";
  private static final String TEST_USER = "thutmosis";

  private final static TestEnvironment testEnvironment = new TestEnvironment(APP_NAME);
  private final static CassandraInitializer cassandraInitializer = new CassandraInitializer();
  private final static MariaDBInitializer mariaDBInitializer = new MariaDBInitializer();
  private final static TenantDataStoreContextTestRule tenantDataStoreContext = TenantDataStoreContextTestRule.forRandomTenantName(cassandraInitializer, mariaDBInitializer);

  @ClassRule
  public static TestRule orderClassRules = RuleChain
          .outerRule(testEnvironment)
          .around(cassandraInitializer)
          .around(mariaDBInitializer)
          .around(tenantDataStoreContext);

  @Rule
  public final TenantApplicationSecurityEnvironmentTestRule tenantApplicationSecurityEnvironment
          = new TenantApplicationSecurityEnvironmentTestRule(testEnvironment, this::waitForInitialize);

  @Autowired
  private OrganizationManager organizationManager;

  @Autowired
  private EventRecorder eventRecorder;

  private AutoUserContext userContext;

  @Before
  public void prepareTest() {
    userContext = tenantApplicationSecurityEnvironment.createAutoUserContext(TestOffice.TEST_USER);
  }

  @After
  public void cleanupTest() {
    userContext.close();
  }

  public boolean waitForInitialize() {
    try {
      return this.eventRecorder.wait(EventConstants.INITIALIZE, EventConstants.INITIALIZE);
    } catch (final InterruptedException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  public void shouldCreateOffice() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());

    final Office savedOffice = this.organizationManager.findOfficeByIdentifier(office.getIdentifier());
    Assert.assertNotNull(savedOffice);
    Assert.assertEquals(office.getIdentifier(), savedOffice.getIdentifier());
    Assert.assertEquals(office.getName(), savedOffice.getName());
    Assert.assertEquals(office.getDescription(), savedOffice.getDescription());

    this.organizationManager.deleteOffice(office.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());

    this.mockMvc.perform(post(path + "/offices").contentType(MediaType.APPLICATION_JSON_VALUE)
            .content(savedOffice.getIdentifier()).accept(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(status().isNotFound());
  }

  @Test
  public void shouldNotCreateOfficeDuplicate() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());
    try {
      this.organizationManager.createOffice(office);
      Assert.fail();
    } catch (final AlreadyExistsException ex) {
      // do nothing, expected
    }

    this.organizationManager.deleteOffice(office.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());
  }

  @Test
  public void shouldUpdateOffice() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());

    final String modifiedOfficeName = RandomStringUtils.randomAlphanumeric(32);
    office.setName(modifiedOfficeName);

    this.organizationManager.updateOffice(office.getIdentifier(), office);
    this.eventRecorder.wait(EventConstants.OPERATION_PUT_OFFICE, office.getIdentifier());

    final Office changedOffice = this.organizationManager.findOfficeByIdentifier(office.getIdentifier());
    Assert.assertEquals(modifiedOfficeName, changedOffice.getName());

    this.organizationManager.deleteOffice(office.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());

    this.mockMvc.perform(put(path + "/offices/" + changedOffice.getIdentifier())
            .contentType(MediaType.APPLICATION_JSON)
            .content(changedOffice.getName()))
            .andExpect(status().is4xxClientError());
  }

  @Test
  public void shouldNotUpdateOfficeIdentifierMismatch() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());

    final String originalIdentifier = office.getIdentifier();
    office.setIdentifier(RandomStringUtils.randomAlphanumeric(32));

    try {
      this.organizationManager.updateOffice(originalIdentifier, office);
      Assert.fail();
    } catch (final BadRequestException ex) {
      // do nothing, expected
    }
    this.organizationManager.deleteOffice(originalIdentifier);
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());
  }

  @Test
  public void shouldAddBranch() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());

    final Office branch = OfficeFactory.createRandomOffice();
    this.organizationManager.addBranch(office.getIdentifier(), branch);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, branch.getIdentifier());

    final OfficePage officePage = this.organizationManager.getBranches(office.getIdentifier(), 0, 10, null, null);
    Assert.assertEquals(Long.valueOf(1L), officePage.getTotalElements());

    final Office savedBranch = officePage.getOffices().get(0);
    Assert.assertEquals(branch.getIdentifier(), savedBranch.getIdentifier());

    this.organizationManager.deleteOffice(branch.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, branch.getIdentifier());
    this.organizationManager.deleteOffice(office.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());

    this.mockMvc.perform(put(path + "/offices/" + branch.getIdentifier())
            .contentType(MediaType.APPLICATION_JSON)
            .content(branch.getIdentifier()))
            .andExpect(status().is4xxClientError());
  }

  @Test
  public void shouldNotAddBranchParentNotFound() throws Exception {
    try {
      final Office branch = OfficeFactory.createRandomOffice();
      this.organizationManager.addBranch(RandomStringUtils.randomAlphanumeric(32), branch);
      Assert.fail();
    } catch (final NotFoundException ex) {
      // do nothing, expected
    }
  }

  @Test
  public void shouldNotAddBranchDuplicate() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());
    try {
      this.organizationManager.addBranch(office.getIdentifier(), office);
      Assert.fail();
    } catch (final AlreadyExistsException ex) {
      // do nothing, expected
    }

    this.organizationManager.deleteOffice(office.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());
  }

  @Test
  public void shouldSetAddressOfOffice() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());

    final Address address = AddressFactory.createRandomAddress();
    this.organizationManager.setAddressForOffice(office.getIdentifier(), address);
    this.eventRecorder.wait(EventConstants.OPERATION_PUT_ADDRESS, office.getIdentifier());

    final Address savedAddress = this.organizationManager.getAddressOfOffice(office.getIdentifier());
    Assert.assertNotNull(savedAddress);
    Assert.assertEquals(address.getStreet(), savedAddress.getStreet());
    Assert.assertEquals(address.getCity(), savedAddress.getCity());
    Assert.assertEquals(address.getRegion(), savedAddress.getRegion());
    Assert.assertEquals(address.getPostalCode(), savedAddress.getPostalCode());
    Assert.assertEquals(address.getCountryCode(), savedAddress.getCountryCode());
    Assert.assertEquals(address.getCountry(), savedAddress.getCountry());

    this.organizationManager.deleteOffice(office.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());

    this.mockMvc.perform(put(path + "/offices/" + office.getIdentifier() + "/address")
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON_VALUE)
            .content(office.getIdentifier()))
            .andExpect(status().is4xxClientError());
  }

  @Test
  public void shouldGetAddressOfOffice() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    final Address address = AddressFactory.createRandomAddress();
    office.setAddress(address);
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());

    final Address savedAddress = this.organizationManager.getAddressOfOffice(office.getIdentifier());
    Assert.assertNotNull(savedAddress);
    Assert.assertEquals(address.getStreet(), savedAddress.getStreet());
    Assert.assertEquals(address.getCity(), savedAddress.getCity());
    Assert.assertEquals(address.getRegion(), savedAddress.getRegion());
    Assert.assertEquals(address.getPostalCode(), savedAddress.getPostalCode());
    Assert.assertEquals(address.getCountryCode(), savedAddress.getCountryCode());
    Assert.assertEquals(address.getCountry(), savedAddress.getCountry());

    this.organizationManager.deleteOffice(office.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());

    this.mockMvc.perform(get(path + "/offices/" + office.getIdentifier() + "/address")
            .accept(MediaType.ALL).contentType(MediaType.APPLICATION_JSON_VALUE))
            .andExpect(status().isNotFound());
  }

  @Test
  public void shouldDeleteAddressOfOffice() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    final Address address = AddressFactory.createRandomAddress();
    office.setAddress(address);
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());

    final Address savedAddress = this.organizationManager.getAddressOfOffice(office.getIdentifier());
    Assert.assertNotNull(savedAddress);

    this.organizationManager.deleteAddressOfOffice(office.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_ADDRESS, office.getIdentifier());

    Assert.assertNull(this.organizationManager.getAddressOfOffice(office.getIdentifier()));

    this.organizationManager.deleteOffice(office.getIdentifier());
    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());

    this.mockMvc.perform(delete(path + "/offices/" + office.getIdentifier() + "/address")
            .contentType(MediaType.ALL_VALUE))
            .andExpect(status().isNotFound());
  }

  @Test
  public void shouldReturnParentOfBranch() throws Exception {
    final Office parent = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(parent);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, parent.getIdentifier());

    final Office branch = OfficeFactory.createRandomOffice();
    this.organizationManager.addBranch(parent.getIdentifier(), branch);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, branch.getIdentifier());

    final Office savedBranch = this.organizationManager.findOfficeByIdentifier(branch.getIdentifier());

    Assert.assertEquals(parent.getIdentifier(), savedBranch.getParentIdentifier());
  }

  @Test(expected = NotFoundException.class)
  public void shouldDeleteOffice() throws Exception{
    final Office office = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());

    this.organizationManager.deleteOffice(office.getIdentifier());

    this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, office.getIdentifier());

    this.organizationManager.findOfficeByIdentifier(office.getIdentifier());

    this.mockMvc.perform(delete(path + "/offices/" + office.getIdentifier())
            .contentType(MediaType.ALL_VALUE))
            .andExpect(status().isNotFound());
  }

  @Test(expected = ChildrenExistException.class)
  public void shouldNotDeleteOfficeWithBranches() throws Exception {
    final Office parent = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(parent);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, parent.getIdentifier());

    final Office branch = OfficeFactory.createRandomOffice();
    this.organizationManager.addBranch(parent.getIdentifier(), branch);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, branch.getIdentifier());

    final Office fetchedParent = this.organizationManager.findOfficeByIdentifier(parent.getIdentifier());
    Assert.assertTrue(fetchedParent.getExternalReferences());

    this.organizationManager.deleteOffice(parent.getIdentifier());
  }

  @Test(expected = ChildrenExistException.class)
  public void shouldNotDeleteOfficeWithEmployees() throws Exception {
    final Office office = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(office);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, office.getIdentifier());

    final Employee employee = EmployeeFactory.createRandomEmployee();
    employee.setAssignedOffice(office.getIdentifier());
    this.organizationManager.createEmployee(employee);
    this.eventRecorder.wait(EventConstants.OPERATION_POST_EMPLOYEE, employee.getIdentifier());

    final Office fetchedOffice = this.organizationManager.findOfficeByIdentifier(office.getIdentifier());
    Assert.assertTrue(fetchedOffice.getExternalReferences());

    this.organizationManager.deleteOffice(office.getIdentifier());
  }

  @Test(expected = ChildrenExistException.class)
  public void shouldNotDeleteOfficeWithActiveExternalReference() throws Exception {
    final Office randomOffice = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(randomOffice);
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, randomOffice.getIdentifier()));

    final ExternalReference externalReference = new ExternalReference();
    externalReference.setType("anytype");
    externalReference.setState(ExternalReference.State.ACTIVE.name());

    this.organizationManager.addExternalReference(randomOffice.getIdentifier(), externalReference);
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_PUT_REFERENCE, randomOffice.getIdentifier()));

    this.organizationManager.deleteOffice(randomOffice.getIdentifier());
  }

  @Test
  public void shouldDeleteOfficeWithInactiveExternalReference() throws Exception {
    final Office randomOffice = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(randomOffice);
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, randomOffice.getIdentifier()));

    final ExternalReference externalReference = new ExternalReference();
    externalReference.setType("anytype");
    externalReference.setState(ExternalReference.State.INACTIVE.name());

    this.organizationManager.addExternalReference(randomOffice.getIdentifier(), externalReference);
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_PUT_REFERENCE, randomOffice.getIdentifier()));

    final Office office = this.organizationManager.findOfficeByIdentifier(randomOffice.getIdentifier());
    Assert.assertFalse(office.getExternalReferences());

    this.organizationManager.deleteOffice(randomOffice.getIdentifier());
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_DELETE_OFFICE, randomOffice.getIdentifier()));
  }

  @Test
  public void shouldIndicateOfficeHasExternalReferences() throws Exception {
    final Office randomOffice = OfficeFactory.createRandomOffice();
    this.organizationManager.createOffice(randomOffice);
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_POST_OFFICE, randomOffice.getIdentifier()));

    final ExternalReference externalReference = new ExternalReference();
    externalReference.setType("anytype");
    externalReference.setState(ExternalReference.State.ACTIVE.name());

    this.organizationManager.addExternalReference(randomOffice.getIdentifier(), externalReference);
    Assert.assertTrue(this.eventRecorder.wait(EventConstants.OPERATION_PUT_REFERENCE, randomOffice.getIdentifier()));

    final Office office = this.organizationManager.findOfficeByIdentifier(randomOffice.getIdentifier());
    Assert.assertTrue(office.getExternalReferences());

    this.mockMvc.perform(put(path + "/offices/" + office.getIdentifier() + "/references")
            .contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON_VALUE)
            .content(office.getIdentifier()))
            .andExpect(status().is4xxClientError());
  }

  @Configuration
  @ComponentScan(
          basePackages = "org.apache.fineract.cn.office.listener"
  )
  @EnableFeignClients(basePackages = {"org.apache.fineract.cn.office.api.v1.client"})
  @RibbonClient(name = APP_NAME)
  @EnableEventRecording(maxWait = 5000L)
  @Import({OfficeRestConfiguration.class})
  public static class TestConfiguration {
    public TestConfiguration() {
      super();
    }

    @Bean
    public Logger logger() {
      return LoggerFactory.getLogger("office-test-logger");
    }
  }
}
