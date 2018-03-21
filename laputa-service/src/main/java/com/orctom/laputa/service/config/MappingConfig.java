package com.orctom.laputa.service.config;

import com.google.common.base.Strings;
import com.orctom.laputa.exception.IllegalArgException;
import com.orctom.laputa.service.annotation.DELETE;
import com.orctom.laputa.service.annotation.GET;
import com.orctom.laputa.service.annotation.HEAD;
import com.orctom.laputa.service.annotation.OPTIONS;
import com.orctom.laputa.service.annotation.PATH;
import com.orctom.laputa.service.annotation.POST;
import com.orctom.laputa.service.annotation.PUT;
import com.orctom.laputa.service.annotation.RedirectTo;
import com.orctom.laputa.service.controller.DefaultController;
import com.orctom.laputa.service.model.HTTPMethod;
import com.orctom.laputa.service.model.PathTrie;
import com.orctom.laputa.service.model.RequestMapping;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.orctom.laputa.service.Constants.PATH_SEPARATOR;
import static com.orctom.laputa.service.Constants.SIGN_DOT;

/**
 * Holding url mappings...
 * Created by hao on 9/21/15.
 */
public class MappingConfig {

  private static final Logger LOGGER = LoggerFactory.getLogger(MappingConfig.class);
  private static final Pattern PATTERN_DOUBLE_SLASHES = Pattern.compile("/+");
  private static final Pattern PATTERN_TAIL_SLASH = Pattern.compile("/$");
  private static final String KEY_PATH_PARAM = "{*}";
  private static final String MAPPING_404 = "/404/@get";
  private static final MappingConfig INSTANCE = new MappingConfig();
  private static final String INDEX = "index";
  private Map<String, RequestMapping> staticMappings = new HashMap<>();
  private PathTrie wildcardMappings = new PathTrie();

  private MappingConfig() {
  }

  public static MappingConfig getInstance() {
    return INSTANCE;
  }

  public RequestMapping getMapping(String uri, HTTPMethod httpMethod) {
    String path = uri;

    int dotIndex = path.lastIndexOf(SIGN_DOT);
    if (dotIndex > 0) {
      path = path.substring(0, dotIndex);
    }

    boolean isPathEndWithSlash = path.endsWith(PATH_SEPARATOR);
    String key = isPathEndWithSlash ? path + httpMethod.getKey() : path + PATH_SEPARATOR + httpMethod.getKey();

    RequestMapping handler = staticMappings.get(key);
    if (null != handler) {
      return handler;
    }

    if (isPathEndWithSlash) {
      handler = staticMappings.get(path + INDEX + httpMethod.getKey());
      if (null != handler) {
        return handler;
      }
    }

    handler = getHandlerForWildcardUri(path, httpMethod);
    if (null != handler) {
      return handler;
    }

    if (isPathEndWithSlash) {
      handler = getHandlerForWildcardUri(path + INDEX, httpMethod);
      if (null != handler) {
        return handler;
      }
    }

    return null;
  }

  public RequestMapping _404() {
    return staticMappings.get(MAPPING_404);
  }

  /**
   * There are 4 types of `paths`:<br/>
   * <li>1) static at middle of the uri</li>
   * <li>2) static at end of the uri</li>
   * <li>3) dynamic at middle of the uri</li>
   * <li>4) dynamic at end of the uri</li>
   */
  private RequestMapping getHandlerForWildcardUri(String uri, HTTPMethod httpMethod) {
    String[] paths = uri.split("/");
    if (paths.length < 2) {
      return null;
    }

    PathTrie parent = wildcardMappings;

    for (int i = 1; i < paths.length; i++) {
      String path = paths[i];
      if (Strings.isNullOrEmpty(path)) {
        continue;
      }

      boolean isEndPath = i == paths.length - 1;

      // 1) and 2)
      PathTrie child = parent.getChildren().get(path);
      if (null != child) {
        if (isEndPath) {
          return getRequestMappingFromChildren(httpMethod, child);
        }
        parent = child;
        continue;
      }

      // 3) and 4)
      child = parent.getChildren().get(KEY_PATH_PARAM);
      if (null != child) {
        if (isEndPath) {
          return getRequestMappingFromChildren(httpMethod, child);
        }
        parent = child;
      }
    }

    return null;
  }

  private RequestMapping getRequestMappingFromChildren(HTTPMethod httpMethod, PathTrie child) {
    Map<String, PathTrie> children = child.getChildren();
    if (null == children || children.isEmpty()) {
      return null;
    }
    PathTrie node = children.get(httpMethod.getKey());
    if (null == node) {
      return null;
    }
    return node.getHandler();
  }

  public void scan(ApplicationContext applicationContext) {
    Map<String, Object> controllers = applicationContext.getBeansWithAnnotation(Controller.class);
    if (null == controllers || controllers.isEmpty()) {
      throw new IllegalArgException("No @Controllers found in Spring context.");
    }

    configureMappings(applicationContext.getBean(DefaultController.class), DefaultController.class);
    controllers.values().forEach(bean -> configureMappings(bean, bean.getClass()));

    logMappingInfo();
  }

  private void logMappingInfo() {
    if (LOGGER.isInfoEnabled()) {
      LOGGER.info("static mappings:");
      for (RequestMapping handler : new TreeMap<>(staticMappings).values()) {
        LOGGER.info(handler.toString());
      }

      LOGGER.info("dynamic mappings:");
      for (RequestMapping handler : wildcardMappings.getChildrenMappings()) {
        LOGGER.info(handler.toString());
      }
    }
  }

  private void configureMappings(Object instance, Class<?> clazz) {
    String basePath = "";
    if (clazz.isAnnotationPresent(PATH.class)) {
      basePath = clazz.getAnnotation(PATH.class).value();
    }

    for (Method method : clazz.getMethods()) {
      PATH path = AnnotationUtils.findAnnotation(method, PATH.class);
      if (null == path) {
        continue;
      }
      String pathValue = path.value().trim();
      if (Strings.isNullOrEmpty(pathValue)) {
        throw new IllegalArgumentException(
            "Empty value of Path annotation on " + clazz.getCanonicalName() + " " + method.getName());
      }
      String uri = basePath + pathValue;
      addToMappings(instance, clazz, method, uri, path.honorExtension());
    }
  }

  private List<HTTPMethod> getSupportedHTTPMethods(Method method) {
    List<HTTPMethod> supportedHTTPMethods = Arrays.stream(method.getAnnotations())
        .filter(a ->
            a.annotationType() == POST.class ||
                a.annotationType() == PUT.class ||
                a.annotationType() == DELETE.class ||
                a.annotationType() == HEAD.class ||
                a.annotationType() == OPTIONS.class ||
                a.annotationType() == GET.class)
        .map(a -> HTTPMethod.valueOf(a.annotationType().getSimpleName()))
        .collect(Collectors.toList());
    if (supportedHTTPMethods.isEmpty()) {
      supportedHTTPMethods.add(HTTPMethod.GET);
    }
    return supportedHTTPMethods;
  }

  private void addToMappings(Object instance, Class<?> clazz, Method method, String rawPath, boolean honorException) {
    String uri = normalize(rawPath);
    List<HTTPMethod> httpMethods = getSupportedHTTPMethods(method);
    if (null == httpMethods || httpMethods.isEmpty()) {
      return;
    }

    for (HTTPMethod httpMethod : httpMethods) {
      String httpMethodKey = httpMethod.getKey();
      if (uri.contains("{")) {
        addToWildCardMappings(instance, clazz, method, uri, httpMethodKey, honorException);
      } else {
        RequestMapping mapping = staticMappings.put(
            uri + "/" + httpMethodKey,
            RequestMapping.builder()
                .uriPattern(uri)
                .target(instance)
                .handlerClass(clazz)
                .handlerMethod(method)
                .httpMethod(httpMethodKey)
                .redirectTo(getRedirectTo(method))
                .honorExtension(honorException)
                .build()
        );
        if (null != mapping && !(mapping.getTarget() instanceof DefaultController)) {
          throw new IllegalArgumentException("Conflicts found in configured @PATH:\n" + uri + ", " + httpMethodKey +
              "\n\t\t" + mapping.getHandlerMethod().toString() + "\n\t\t" + method.toString());
        }
      }
    }
  }

  private String normalize(String uri) {
    uri = "/" + uri;
    uri = PATTERN_DOUBLE_SLASHES.matcher(uri).replaceAll("/");
    uri = PATTERN_TAIL_SLASH.matcher(uri).replaceAll("");
    return uri;
  }

  private void addToWildCardMappings(Object instance,
                                     Class<?> clazz,
                                     Method method,
                                     String uri,
                                     String httpMethodKey,
                                     boolean honorException) {
    String[] paths = uri.split("/");

    if (paths.length < 2) {
      return;
    }

    Map<String, PathTrie> children = wildcardMappings.getChildren();

    for (String path : paths) {
      if (Strings.isNullOrEmpty(path)) {
        continue;
      }

      boolean containsParam = path.contains("{");
      String pathKey = containsParam ? KEY_PATH_PARAM : path;
      PathTrie child = children.computeIfAbsent(pathKey, k -> new PathTrie());

      children = child.getChildren();
    }

    PathTrie leaf = new PathTrie(uri, instance, clazz, method, httpMethodKey, getRedirectTo(method), honorException);
    children.put(httpMethodKey, leaf);
  }

  private String getRedirectTo(Method method) {
    RedirectTo redirectTo = method.getAnnotation(RedirectTo.class);
    if (null == redirectTo) {
      return null;
    }
    String redirectToValue = redirectTo.value().trim();
    if (Strings.isNullOrEmpty(redirectToValue)) {
      throw new IllegalArgumentException("Empty value of RedirectTo annotation on " + method.toString());
    }
    return redirectToValue;
  }
}
