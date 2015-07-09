package com.webcohesion.enunciate.examples.jaxwsrijersey.genealogy.services;

import com.webcohesion.enunciate.examples.jaxwsrijersey.genealogy.data.PersonAdmin;
import com.webcohesion.enunciate.metadata.Facet;

import javax.jws.WebService;

@Facet(name = "http://enunciate.codehaus.org/samples/full#admin" )
@WebService(targetNamespace = "http://enunciate.codehaus.org/samples/full")
public interface AdminService {

    PersonAdmin readAdminPerson(String id);
}