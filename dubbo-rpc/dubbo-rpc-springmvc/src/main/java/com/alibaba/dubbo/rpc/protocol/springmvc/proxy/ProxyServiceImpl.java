package com.alibaba.dubbo.rpc.protocol.springmvc.proxy;

import com.alibaba.dubbo.config.RegistryConfig;
import com.alibaba.dubbo.config.spring.ReferenceBean;
import com.alibaba.dubbo.config.spring.ServiceBean;
import com.alibaba.dubbo.rpc.RpcException;
import com.alibaba.dubbo.rpc.service.GenericService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.TypeFactory;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.method.HandlerMethodSelector;
import rx.Observable;
import rx.Subscriber;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * Created by wuyu on 2016/7/14.
 */
public class ProxyServiceImpl implements ProxyService, DisposableBean, ApplicationContextAware {


    private Map<String, GenericService> genericServiceMap = new ConcurrentHashMap<String, GenericService>();

    private Map<String, Class> clazzCache = new ConcurrentHashMap<>();

    private ApplicationContext applicationContext;

    private ObjectMapper objectMapper = new ObjectMapper();


    /**
     * http://localhost:8080/
     * POST,PUT,DELETE
     * 调用示例
     * {
     * "jsonrpc":2.0 ,//兼容jsonrpc， 如果携带次参数 将以jsonrpc 格式返回
     * "service":"com.alibaba.dubbo.demo.DemoService",
     * "method":"sayHello", //可以以 com.alibaba.dubbo.demo.DemoService.sayHello 来省略 service
     * "group":"defaultGroup",//可以不写
     * "version":"1.0" ,//可以不写
     * "paramsType":["java.lang.String"], //可以不写
     * "params":["wuyu"]
     * }
     *
     * @param config
     * @return
     */
    @ResponseBody
    public Object proxy(@RequestBody GenericServiceConfig config) {
        if (config.getMethod() == null) {
            throw new IllegalArgumentException(config.toString() + " Miss required parameter! ");
        }

        String service = config.getService();
        String methodName = config.getMethod();

        if (config.getJsonrpc() != null && service == null) {
            service = methodName.substring(0, methodName.lastIndexOf("."));
            methodName = methodName.substring(methodName.lastIndexOf(".")).replace(".", "");
            config.setService(service);
            config.setMethod(methodName);
        }


        try {
            Class clazz = getClazz(service);
            Object result = null;
            if (clazz != null) {
                Method method = getMethod(config.getService(), config.getMethod(), config.getParams(), config.getParamsType());
                Object[] args = convertArgs(method, config.getParams());
                //判断本地是否有相关bean,如果有直接调用
                Map beans = applicationContext.getBeansOfType(getClazz(config.getService()));
                if (beans.size() >= 1) {
                    Object bean = beans.values().iterator().next();
                    result = method.invoke(bean, args);
                    if (result instanceof Future) {
                        result = ((Future) result).get();
                    } else if (result instanceof Observable) {
                        final List<Object> list = new ArrayList<>();
                        final List<Throwable> error = new ArrayList<>();
                        Observable observable = (Observable) result;
                        observable.subscribe(new Subscriber() {
                            @Override
                            public void onCompleted() {
                            }

                            @Override
                            public void onError(Throwable e) {
                                error.add(e);
                            }

                            @Override
                            public void onNext(Object o) {
                                list.add(o);
                            }
                        });

                        if (error.size() > 0) {
                            throw error.get(0);
                        }

                        result = list;
                    }
                } else {
                    result = genericService(config).$invoke(methodName, config.getParamsType(), args);
                }
            }

            return config.getJsonrpc() == null ? result : createSuccessResponse(config.getJsonrpc(), config.getId(), result);

        } catch (Throwable e) {
            if (config.getJsonrpc() != null) {
                e.printStackTrace();
                return new ResponseEntity<Object>(createErrorResponse(config.getJsonrpc(), config.getId(), -32600, e.toString(), null), HttpStatus.INTERNAL_SERVER_ERROR);
            }
            throw new RpcException(e);
        }
    }


    protected Object[] convertArgs(Method method, String[] args) throws IOException {
        Object[] convertArgs = new Object[args.length];
        Type[] genericParameterTypes = method.getGenericParameterTypes();
        for (int i = 0; i < genericParameterTypes.length; i++) {
            JavaType javaType = TypeFactory.defaultInstance().constructType(genericParameterTypes[i]);
            if (String.class.isAssignableFrom(method.getReturnType())) {
                convertArgs[i] = args[i];
            } else if (objectMapper.canDeserialize(javaType)) {
                convertArgs[i] = objectMapper.readValue(args[i], javaType);
            } else {
                convertArgs[i] = objectMapper.convertValue(args[i], javaType);

            }
        }

        return convertArgs;
    }

    protected Method getMethod(String serviceName, final String methodName, final String[] params, String[] paramsType) throws ClassNotFoundException, NoSuchMethodException {
        Class serviceClazz = getClazz(serviceName);
        Class[] clazzTypes = new Class[params.length];

        if (paramsType == null || paramsType.length == 0) {
            Set<Method> methods = HandlerMethodSelector.selectMethods(serviceClazz, new ReflectionUtils.MethodFilter() {
                @Override
                public boolean matches(Method method) {
                    return method.getName().equals(methodName) && method.getParameterTypes().length == params.length;
                }
            });
            if (methods.size() == 1) {
                return methods.iterator().next();
            }
        }

        if (paramsType != null) {
            for (int i = 0; i < paramsType.length; i++) {
                clazzTypes[i] = getClazz(paramsType[i]);
            }
            return serviceClazz.getMethod(methodName, clazzTypes);
        }
        throw new NoSuchMethodException("Method not find!");
    }


    protected Class getClazz(String clazzName) throws ClassNotFoundException {
        Class aClass = clazzCache.get(clazzName);
        if (aClass != null) {
            return aClass;
        }

        aClass = Class.forName(clazzName);
        clazzCache.put(clazzName, aClass);
        return aClass;
    }

    protected GenericService genericService(GenericServiceConfig config) {
        String key = sliceKey(config);
        GenericService genericService = genericServiceMap.get(key);
        if (genericService != null) {
            return genericService;
        }
        ApplicationContext springContext = ServiceBean.getSpringContext();
        ReferenceBean<GenericService> reference = new ReferenceBean<GenericService>(); // 该实例很重量，里面封装了所有与注册中心及服务提供方连接，请缓存
        reference.setApplicationContext(springContext);
        reference.setInterface(config.getService()); // 弱类型接口名
        if (config.getVersion() != null && !config.getVersion().equals("0.0.0")) {
            reference.setVersion(config.getVersion());
        }
        if (config.getGroup() != null && (!config.getGroup().equalsIgnoreCase("defaultGroup"))) {
            reference.setGroup(config.getGroup());
        }

        String[] registries = springContext.getBeanNamesForType(RegistryConfig.class);
        for (String registry : registries) {
            reference.setRegistry(springContext.getBean(registry, RegistryConfig.class));
        }
        reference.setGeneric(true); // 声明为泛化接口
        genericService = reference.get();
        genericServiceMap.put(key, genericService);
        return genericService; // 用com.alibaba.dubbo.rpc.service.GenericService可以替代所有接口引用
    }

    private String sliceKey(GenericServiceConfig config) {
        return "/" + config.getGroup() + "/" + config.getVersion() + "/" + config.getService();
    }


    protected JSONObject createErrorResponse(String jsonRpc, Object id, int code, String message, Object data) {
        JSONObject error = new JSONObject();
        JSONObject response = new JSONObject();
        error.put("code", code);
        error.put("message", message);
        if (data != null) {
            error.put("data", JSON.toJSONString(data));
        }
        response.put("jsonrpc", jsonRpc);
        if (Integer.class.isInstance(id)) {
            response.put("id", Integer.class.cast(id).intValue());
        } else if (Long.class.isInstance(id)) {
            response.put("id", Long.class.cast(id).longValue());
        } else if (Float.class.isInstance(id)) {
            response.put("id", Float.class.cast(id).floatValue());
        } else if (Double.class.isInstance(id)) {
            response.put("id", Double.class.cast(id).doubleValue());
        } else if (BigDecimal.class.isInstance(id)) {
            response.put("id", BigDecimal.class.cast(id));
        } else {
            response.put("id", String.class.cast(id));
        }
        response.put("error", error);
        return response;
    }

    protected JSONObject createSuccessResponse(String jsonRpc, Object id, Object result) {
        JSONObject response = new JSONObject();
        response.put("jsonrpc", jsonRpc);
        if (Integer.class.isInstance(id)) {
            response.put("id", Integer.class.cast(id).intValue());
        } else if (Long.class.isInstance(id)) {
            response.put("id", Long.class.cast(id).longValue());
        } else if (Float.class.isInstance(id)) {
            response.put("id", Float.class.cast(id).floatValue());
        } else if (Double.class.isInstance(id)) {
            response.put("id", Double.class.cast(id).doubleValue());
        } else if (BigDecimal.class.isInstance(id)) {
            response.put("id", BigDecimal.class.cast(id));
        } else {
            response.put("id", String.class.cast(id));
        }
        response.put("result", result);
        return response;
    }


    @Override
    public void destroy() throws Exception {
        genericServiceMap.clear();
        applicationContext = null;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
