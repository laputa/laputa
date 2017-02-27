package com.orctom.laputa.service.internal.handler;

import com.orctom.laputa.service.annotation.Path;

import static com.orctom.laputa.service.Constants.*;

/**
 * Default handler
 * Created by hao on 11/17/15.
 */
public class DefaultHandler {

  @Path(PATH_FAVICON)
  public String _favicon() {
    return null;
  }

  @Path(PATH_404)
  public String _404() {
    return "The requested resource does not exist.";
  }

  @Path(PATH_500)
  public String _500() {
    return "The server can not process your last request, please try again later.";
  }
}
