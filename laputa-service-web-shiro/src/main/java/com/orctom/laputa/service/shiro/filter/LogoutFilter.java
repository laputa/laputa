package com.orctom.laputa.service.shiro.filter;

public class LogoutFilter extends PathMatchingFilter {

  @Override
  public String getName() {
    return null;
  }

  @Override
  protected boolean isAccessAllowed() {
    return false;
  }
}
